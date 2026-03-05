package com.looped.communities;

import com.looped.email.EmailService;
import com.looped.notifications.NotificationPublisher;
import com.looped.verification.ThirdPartyVerifier;
import com.looped.verification.VerificationProperties;
import com.looped.verification.VerificationRequestsRepository;
import com.looped.users.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.looped.communities.CommunityVisibilityRules.isUserVisible;

@Service
public class CommunityVerificationService {
    public enum Method { email, video, thirdparty }
    public enum Status { OK, USER_NOT_PROVISIONED, COMMUNITY_NOT_FOUND, BAD_REQUEST, INVALID_CODE, SEND_FAILED, CONFLICT, RATE_LIMITED }

    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityDomainsRepository communityDomains;
    private final CommunityVerificationsRepository communityVerifications;
    private final VerificationRequestsRepository requests;
    private final StringRedisTemplate redis;
    private final VerificationProperties props;
    private final ThirdPartyVerifier thirdPartyVerifier;
    private final EmailService emailService;
    private final NotificationPublisher notifications;
    private final SecureRandom random = new SecureRandom();

    public CommunityVerificationService(UserRepository users,
                                        CommunitiesRepository communities,
                                        CommunityDomainsRepository communityDomains,
                                        CommunityVerificationsRepository communityVerifications,
                                        VerificationRequestsRepository requests,
                                        StringRedisTemplate redis,
                                        VerificationProperties props,
                                        ThirdPartyVerifier thirdPartyVerifier,
                                        EmailService emailService,
                                        NotificationPublisher notifications) {
        this.users = users;
        this.communities = communities;
        this.communityDomains = communityDomains;
        this.communityVerifications = communityVerifications;
        this.requests = requests;
        this.redis = redis;
        this.props = props;
        this.thirdPartyVerifier = thirdPartyVerifier;
        this.emailService = emailService;
        this.notifications = notifications;
    }

    public StartResult start(String firebaseUid, long communityId, String methodStr, String email) {
        var method = parseMethod(methodStr);
        if (method == null) return StartResult.badRequest("unsupported_method");

        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty() || u.get().companyId == null) return StartResult.userNotProvisioned();
        var community = communities.findById(communityId);
        if (community.isEmpty()) return StartResult.communityNotFound();
        if (!isUserVisible(community.get().kind, community.get().specializationType)) {
            return StartResult.communityNotFound();
        }
        if ("specialization".equalsIgnoreCase(community.get().kind)) {
            return StartResult.badRequest("verification_not_supported");
        }

        String devCode = null;
        String sessionId = null;
        String instructions = null;
        switch (method) {
            case email -> {
                String normalizedEmail = normalizeEmail(email);
                if (normalizedEmail == null) return StartResult.badRequest("email_required");
                String domain = extractDomain(normalizedEmail);
                if (domain == null) return StartResult.badRequest("invalid_email");
                if (!hasEffectiveDomains(community.get())) return StartResult.badRequest("domains_not_configured");
                if (!isDomainAllowed(community.get(), domain)) return StartResult.badRequest("email_domain_not_allowed");
                communityVerifications.expireExpiredForEmailNow(communityId, normalizedEmail);
                var owner = communityVerifications.findActiveOwnerUserId(communityId, normalizedEmail);
                if (owner.isPresent() && owner.get() != u.get().id) {
                    return StartResult.conflict("email_in_use");
                }
                var rateLimit = reserveEmailStartBudget(u.get().id, communityId);
                if (rateLimit != null) {
                    return StartResult.rateLimited(rateLimit.error(), rateLimit.retryAfterSeconds());
                }
                String code = generateCode6();
                String key = keyEmail(u.get().id, communityId);
                redis.opsForValue().set(key, code, Duration.ofSeconds(props.getCodeTtlSeconds()));
                String emailKey = keyEmailAddress(u.get().id, communityId);
                redis.opsForValue().set(emailKey, normalizedEmail, Duration.ofSeconds(props.getCodeTtlSeconds()));
                redis.delete(keyEmailAttempts(u.get().id, communityId));
                if (!emailService.isEnabled()) {
                    if (!props.isEchoCode()) return StartResult.sendFailed();
                } else {
                    try {
                        emailService.sendCommunityVerificationEmail(normalizedEmail, communityId, community.get().name, code, props.getCodeTtlSeconds());
                    } catch (RuntimeException ex) {
                        return StartResult.sendFailed();
                    }
                }
                if (props.isEchoCode()) devCode = code;
                instructions = "Check your email for a 6-digit code and call finish with that code.";
            }
            case video -> instructions = "Upload a short verification video via /v1/media/presign (video/mp4) and call finish with mediaKey.";
            case thirdparty -> {
                sessionId = UUID.randomUUID().toString();
                String key = keyThirdParty(u.get().id, communityId);
                redis.opsForValue().set(key, sessionId, Duration.ofMinutes(30));
                instructions = "Complete verification with the third-party provider using sessionId, then call finish with the provider token.";
            }
        }
        return StartResult.ok(method.name(), devCode, sessionId, instructions);
    }

    public FinishResult finish(String firebaseUid, long communityId, String requestEmail, String fallbackEmail, String methodStr,
                               String code, String mediaKey, String token) {
        var method = parseMethod(methodStr);
        if (method == null) return FinishResult.badRequest("unsupported_method");

        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty() || u.get().companyId == null) return FinishResult.userNotProvisioned();
        var community = communities.findById(communityId);
        if (community.isEmpty()) return FinishResult.communityNotFound();
        if (!isUserVisible(community.get().kind, community.get().specializationType)) {
            return FinishResult.communityNotFound();
        }
        if ("specialization".equalsIgnoreCase(community.get().kind)) {
            return FinishResult.badRequest("verification_not_supported");
        }

        String resolvedEmail;
        if (method == Method.email) {
            String cached = normalizeEmail(redis.opsForValue().get(keyEmailAddress(u.get().id, communityId)));
            if (cached == null) return FinishResult.invalidCode();

            String requested = normalizeEmail(requestEmail);
            if (requested != null && !requested.equals(cached)) {
                return FinishResult.badRequest("email_mismatch");
            }
            resolvedEmail = cached;
            if (resolvedEmail == null) return FinishResult.badRequest("email_required");
            String domain = extractDomain(resolvedEmail);
            if (domain == null) return FinishResult.badRequest("invalid_email");
            if (!hasEffectiveDomains(community.get())) return FinishResult.badRequest("domains_not_configured");
            if (!isDomainAllowed(community.get(), domain)) return FinishResult.badRequest("email_domain_not_allowed");
        } else {
            resolvedEmail = resolveRequestEmail(method, requestEmail, fallbackEmail);
        }

        switch (method) {
            case email -> {
                if (code == null || code.isBlank()) return FinishResult.badRequest("code_required");
                String key = keyEmail(u.get().id, communityId);
                String expected = redis.opsForValue().get(key);
                if (expected == null || !expected.equals(code)) {
                    if (expected == null) return FinishResult.invalidCode();
                    long attempts = incrementAttemptsWithTtl(
                            keyEmailAttempts(u.get().id, communityId),
                            Duration.ofSeconds(props.getCodeTtlSeconds())
                    );
                    if (attempts >= Math.max(1, props.getEmailCodeMaxAttempts())) {
                        clearEmailChallenge(u.get().id, communityId);
                        return FinishResult.rateLimited("too_many_attempts", null);
                    }
                    return FinishResult.invalidCode();
                }
                clearEmailChallenge(u.get().id, communityId);
            }
            case video -> {
                if (mediaKey == null || mediaKey.isBlank()) return FinishResult.badRequest("media_key_required");
            }
            case thirdparty -> {
                if (token == null || token.isBlank()) return FinishResult.badRequest("token_required");
                String sessKey = keyThirdParty(u.get().id, communityId);
                String sessionId = redis.opsForValue().get(sessKey);
                if (sessionId == null) return FinishResult.badRequest("session_expired");
                boolean ok = thirdPartyVerifier.validate(sessionId, token);
                if (!ok) return FinishResult.badRequest("token_invalid");
                redis.delete(sessKey);
            }
        }
        String status = (method == Method.video) ? "pending" : "approved";
        if ("approved".equals(status)) {
            OffsetDateTime expiresAt = resolveExpiry(community.get());
            if (method == Method.email) {
                communityVerifications.expireExpiredForEmailNow(communityId, resolvedEmail);
                var owner = communityVerifications.findActiveOwnerUserId(communityId, resolvedEmail);
                if (owner.isPresent() && owner.get() != u.get().id) {
                    return FinishResult.conflict("email_in_use");
                }
            }
            try {
                communityVerifications.markVerified(
                        u.get().id,
                        communityId,
                        method.name(),
                        expiresAt,
                        method == Method.email ? resolvedEmail : null
                );
            } catch (DataIntegrityViolationException ex) {
                return FinishResult.conflict("email_in_use");
            }
            long requestId = requests.insert(u.get().id, communityId, resolvedEmail, method.name(), status, mediaKey, null);
            notifications.notifyCommunityVerificationApproved(
                    u.get().id,
                    communityId,
                    community.get().name,
                    method.name(),
                    requestId,
                    expiresAt
            );
            if (method == Method.email && resolvedEmail != null) {
                emailService.sendCommunityVerifiedEmail(resolvedEmail, community.get().name);
            }
            return FinishResult.ok(true, expiresAt);
        }
        requests.insert(u.get().id, communityId, resolvedEmail, method.name(), status, mediaKey, null);
        communityVerifications.markUnverified(u.get().id, communityId, method.name());
        return FinishResult.ok(false, null);
    }

    private Method parseMethod(String m) {
        if (m == null) return null;
        try { return Method.valueOf(m.toLowerCase()); } catch (IllegalArgumentException ex) { return null; }
    }

    private String generateCode6() {
        int n = random.nextInt(900000) + 100000;
        return Integer.toString(n);
    }

    private OffsetDateTime resolveExpiry(CommunitiesRepository.CommunityRow community) {
        Integer ttlDays = community.verificationTtlDays;
        int effectiveTtlDays = ttlDays != null ? ttlDays : props.getDefaultCommunityTtlDays();
        if (effectiveTtlDays > 0) return OffsetDateTime.now().plusDays(effectiveTtlDays);
        return null; // 0 or negative => no expiry
    }

    private String keyEmail(long userId, long communityId) { return "verify:community:email:" + userId + ":" + communityId; }
    private String keyEmailAddress(long userId, long communityId) { return "verify:community:email:addr:" + userId + ":" + communityId; }
    private String keyEmailAttempts(long userId, long communityId) { return "verify:community:email:attempts:" + userId + ":" + communityId; }
    private String keyEmailCooldown(long userId, long communityId) { return "verify:community:email:cooldown:" + userId + ":" + communityId; }
    private String keyEmailStartsHour(long userId, long communityId) { return "verify:community:email:start:hour:" + userId + ":" + communityId; }
    private String keyEmailStartsDay(long userId, long communityId) { return "verify:community:email:start:day:" + userId + ":" + communityId; }
    private String keyThirdParty(long userId, long communityId) { return "verify:community:thirdparty:" + userId + ":" + communityId; }

    private boolean hasEffectiveDomains(CommunitiesRepository.CommunityRow community) {
        return communityDomains.hasDomains(community.id);
    }

    private boolean isDomainAllowed(CommunitiesRepository.CommunityRow community, String domain) {
        return communityDomains.isDomainAllowed(community.id, domain);
    }

    private String resolveRequestEmail(Method method, String requestEmail, String fallbackEmail) {
        String normalized = normalizeEmail(requestEmail);
        if (method == Method.email) {
            if (normalized == null) return null;
            return normalized;
        }
        if (normalized != null) return normalized;
        return normalizeEmail(fallbackEmail);
    }

    private RateLimitResult reserveEmailStartBudget(long userId, long communityId) {
        String cooldownKey = keyEmailCooldown(userId, communityId);
        String hourKey = keyEmailStartsHour(userId, communityId);
        String dayKey = keyEmailStartsDay(userId, communityId);

        Integer cooldownRetry = readPositiveTtlSeconds(cooldownKey);
        if (cooldownRetry != null) {
            return new RateLimitResult("resend_cooldown", cooldownRetry);
        }

        if (!incrementWithinLimit(hourKey, props.getEmailMaxStartsPerHour(), Duration.ofHours(1))) {
            return new RateLimitResult("email_start_rate_limited_hour", readPositiveTtlSeconds(hourKey));
        }
        if (!incrementWithinLimit(dayKey, props.getEmailMaxStartsPerDay(), Duration.ofDays(1))) {
            return new RateLimitResult("email_start_rate_limited_day", readPositiveTtlSeconds(dayKey));
        }

        int cooldownSeconds = props.getEmailResendCooldownSeconds();
        if (cooldownSeconds > 0) {
            redis.opsForValue().set(cooldownKey, "1", Duration.ofSeconds(cooldownSeconds));
        }
        return null;
    }

    private long incrementAttemptsWithTtl(String key, Duration ttl) {
        Long value = redis.opsForValue().increment(key);
        long attempts = value == null ? 1L : value;
        if (attempts == 1L) {
            redis.expire(key, ttl);
        }
        return attempts;
    }

    private boolean incrementWithinLimit(String key, int max, Duration ttl) {
        if (max <= 0) return true;
        Long value = redis.opsForValue().increment(key);
        long count = value == null ? 1L : value;
        if (count == 1L) {
            redis.expire(key, ttl);
        }
        return count <= max;
    }

    private Integer readPositiveTtlSeconds(String key) {
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl <= 0) return null;
        long bounded = Math.min(ttl, Integer.MAX_VALUE);
        return (int) bounded;
    }

    private void clearEmailChallenge(long userId, long communityId) {
        redis.delete(keyEmail(userId, communityId));
        redis.delete(keyEmailAddress(userId, communityId));
        redis.delete(keyEmailAttempts(userId, communityId));
    }

    private String normalizeEmail(String email) {
        if (email == null) return null;
        String trimmed = email.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isBlank()) return null;
        return trimmed;
    }

    private String extractDomain(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) return null;
        return email.substring(at + 1);
    }

    private static String normalizeError(String err) {
        if (err == null || err.isBlank()) return "rate_limited";
        return err.trim().toLowerCase(Locale.ROOT);
    }

    private record RateLimitResult(String error, Integer retryAfterSeconds) {}

    public record StartResult(Status status, String method, String devCode, String sessionId, String instructions, String error, Integer retryAfterSeconds) {
        static StartResult ok(String method, String devCode, String sessionId, String instructions) {
            return new StartResult(Status.OK, method, devCode, sessionId, instructions, null, null);
        }
        static StartResult userNotProvisioned() { return new StartResult(Status.USER_NOT_PROVISIONED, null, null, null, null, null, null); }
        static StartResult communityNotFound() { return new StartResult(Status.COMMUNITY_NOT_FOUND, null, null, null, null, null, null); }
        static StartResult badRequest(String err) { return new StartResult(Status.BAD_REQUEST, null, null, null, null, err, null); }
        static StartResult sendFailed() { return new StartResult(Status.SEND_FAILED, null, null, null, null, "email_send_failed", null); }
        static StartResult conflict(String err) { return new StartResult(Status.CONFLICT, null, null, null, null, err, null); }
        static StartResult rateLimited(String err, Integer retryAfterSeconds) {
            return new StartResult(Status.RATE_LIMITED, null, null, null, null, normalizeError(err), retryAfterSeconds);
        }
    }

    public record FinishResult(Status status, Boolean verified, OffsetDateTime expiresAt, String error, Integer retryAfterSeconds) {
        static FinishResult ok(boolean verified, OffsetDateTime expiresAt) { return new FinishResult(Status.OK, verified, expiresAt, null, null); }
        static FinishResult userNotProvisioned() { return new FinishResult(Status.USER_NOT_PROVISIONED, null, null, null, null); }
        static FinishResult communityNotFound() { return new FinishResult(Status.COMMUNITY_NOT_FOUND, null, null, null, null); }
        static FinishResult badRequest(String err) { return new FinishResult(Status.BAD_REQUEST, null, null, err, null); }
        static FinishResult invalidCode() { return new FinishResult(Status.INVALID_CODE, null, null, "invalid_code", null); }
        static FinishResult conflict(String err) { return new FinishResult(Status.CONFLICT, null, null, err, null); }
        static FinishResult rateLimited(String err, Integer retryAfterSeconds) {
            return new FinishResult(Status.RATE_LIMITED, null, null, normalizeError(err), retryAfterSeconds);
        }
    }
}
