package com.looped.communities;

import com.looped.email.EmailService;
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
import java.util.UUID;

@Service
public class CommunityVerificationService {
    public enum Method { email, video, thirdparty }
    public enum Status { OK, USER_NOT_PROVISIONED, COMMUNITY_NOT_FOUND, BAD_REQUEST, INVALID_CODE, SEND_FAILED, CONFLICT }

    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityDomainsRepository communityDomains;
    private final CommunitySectorLinksRepository sectorLinks;
    private final CommunityVerificationsRepository communityVerifications;
    private final VerificationRequestsRepository requests;
    private final StringRedisTemplate redis;
    private final VerificationProperties props;
    private final ThirdPartyVerifier thirdPartyVerifier;
    private final EmailService emailService;
    private final SecureRandom random = new SecureRandom();

    public CommunityVerificationService(UserRepository users,
                                        CommunitiesRepository communities,
                                        CommunityDomainsRepository communityDomains,
                                        CommunitySectorLinksRepository sectorLinks,
                                        CommunityVerificationsRepository communityVerifications,
                                        VerificationRequestsRepository requests,
                                        StringRedisTemplate redis,
                                        VerificationProperties props,
                                        ThirdPartyVerifier thirdPartyVerifier,
                                        EmailService emailService) {
        this.users = users;
        this.communities = communities;
        this.communityDomains = communityDomains;
        this.sectorLinks = sectorLinks;
        this.communityVerifications = communityVerifications;
        this.requests = requests;
        this.redis = redis;
        this.props = props;
        this.thirdPartyVerifier = thirdPartyVerifier;
        this.emailService = emailService;
    }

    public StartResult start(String firebaseUid, long communityId, String methodStr, String email) {
        var method = parseMethod(methodStr);
        if (method == null) return StartResult.badRequest("unsupported_method");

        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty() || u.get().companyId == null) return StartResult.userNotProvisioned();
        var community = communities.findById(communityId);
        if (community.isEmpty()) return StartResult.communityNotFound();
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
                String code = generateCode6();
                String key = keyEmail(u.get().id, communityId);
                redis.opsForValue().set(key, code, Duration.ofSeconds(props.getCodeTtlSeconds()));
                String emailKey = keyEmailAddress(u.get().id, communityId);
                redis.opsForValue().set(emailKey, normalizedEmail, Duration.ofSeconds(props.getCodeTtlSeconds()));
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
        if ("specialization".equalsIgnoreCase(community.get().kind)) {
            return FinishResult.badRequest("verification_not_supported");
        }

        String resolvedEmail = resolveRequestEmail(method, requestEmail, fallbackEmail);
        if (method == Method.email) {
            if (resolvedEmail == null) {
                String cached = redis.opsForValue().get(keyEmailAddress(u.get().id, communityId));
                if (cached != null) resolvedEmail = cached;
            }
            if (resolvedEmail == null) return FinishResult.badRequest("email_required");
            String domain = extractDomain(resolvedEmail);
            if (domain == null) return FinishResult.badRequest("invalid_email");
            if (!hasEffectiveDomains(community.get())) return FinishResult.badRequest("domains_not_configured");
            if (!isDomainAllowed(community.get(), domain)) return FinishResult.badRequest("email_domain_not_allowed");
        }

        switch (method) {
            case email -> {
                if (code == null || code.isBlank()) return FinishResult.badRequest("code_required");
                String key = keyEmail(u.get().id, communityId);
                String expected = redis.opsForValue().get(key);
                if (expected == null || !expected.equals(code)) return FinishResult.invalidCode();
                redis.delete(key);
                redis.delete(keyEmailAddress(u.get().id, communityId));
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
            requests.insert(u.get().id, communityId, resolvedEmail, method.name(), status, mediaKey, null);
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
    private String keyThirdParty(long userId, long communityId) { return "verify:community:thirdparty:" + userId + ":" + communityId; }

    private boolean hasEffectiveDomains(CommunitiesRepository.CommunityRow community) {
        if (!"sector".equalsIgnoreCase(community.kind)) {
            return communityDomains.hasDomains(community.id);
        }
        if (communityDomains.hasDomains(community.id)) return true;
        var companyIds = sectorLinks.listCompanyIds(community.id);
        return communityDomains.hasDomainsForCommunities(companyIds);
    }

    private boolean isDomainAllowed(CommunitiesRepository.CommunityRow community, String domain) {
        if (communityDomains.isDomainAllowed(community.id, domain)) return true;
        if (!"sector".equalsIgnoreCase(community.kind)) return false;
        var companyIds = sectorLinks.listCompanyIds(community.id);
        return communityDomains.isDomainAllowedForCommunities(companyIds, domain);
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

    private String normalizeEmail(String email) {
        if (email == null) return null;
        String trimmed = email.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.isBlank()) return null;
        return trimmed;
    }

    private String extractDomain(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) return null;
        return email.substring(at + 1);
    }

    public record StartResult(Status status, String method, String devCode, String sessionId, String instructions, String error) {
        static StartResult ok(String method, String devCode, String sessionId, String instructions) {
            return new StartResult(Status.OK, method, devCode, sessionId, instructions, null);
        }
        static StartResult userNotProvisioned() { return new StartResult(Status.USER_NOT_PROVISIONED, null, null, null, null, null); }
        static StartResult communityNotFound() { return new StartResult(Status.COMMUNITY_NOT_FOUND, null, null, null, null, null); }
        static StartResult badRequest(String err) { return new StartResult(Status.BAD_REQUEST, null, null, null, null, err); }
        static StartResult sendFailed() { return new StartResult(Status.SEND_FAILED, null, null, null, null, "email_send_failed"); }
        static StartResult conflict(String err) { return new StartResult(Status.CONFLICT, null, null, null, null, err); }
    }

    public record FinishResult(Status status, Boolean verified, OffsetDateTime expiresAt, String error) {
        static FinishResult ok(boolean verified, OffsetDateTime expiresAt) { return new FinishResult(Status.OK, verified, expiresAt, null); }
        static FinishResult userNotProvisioned() { return new FinishResult(Status.USER_NOT_PROVISIONED, null, null, null); }
        static FinishResult communityNotFound() { return new FinishResult(Status.COMMUNITY_NOT_FOUND, null, null, null); }
        static FinishResult badRequest(String err) { return new FinishResult(Status.BAD_REQUEST, null, null, err); }
        static FinishResult invalidCode() { return new FinishResult(Status.INVALID_CODE, null, null, "invalid_code"); }
        static FinishResult conflict(String err) { return new FinishResult(Status.CONFLICT, null, null, err); }
    }
}
