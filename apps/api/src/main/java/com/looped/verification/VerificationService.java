package com.looped.verification;

import com.looped.email.EmailService;
import com.looped.notifications.NotificationPublisher;
import com.looped.users.UserRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class VerificationService {
    public enum Method { email, video, thirdparty }
    public enum Status { OK, USER_NOT_PROVISIONED, BAD_REQUEST, INVALID_CODE, SEND_FAILED, RATE_LIMITED }

    private final UserRepository users;
    private final VerificationRepository repo;
    private final VerificationRequestsRepository requests;
    private final StringRedisTemplate redis;
    private final VerificationProperties props;
    private final ThirdPartyVerifier thirdPartyVerifier;
    private final EmailService emailService;
    private final NotificationPublisher notifications;
    private final SecureRandom random = new SecureRandom();

    public VerificationService(UserRepository users, VerificationRepository repo, VerificationRequestsRepository requests,
                               StringRedisTemplate redis, VerificationProperties props, ThirdPartyVerifier thirdPartyVerifier,
                               EmailService emailService, NotificationPublisher notifications) {
        this.users = users;
        this.repo = repo;
        this.requests = requests;
        this.redis = redis;
        this.props = props;
        this.thirdPartyVerifier = thirdPartyVerifier;
        this.emailService = emailService;
        this.notifications = notifications;
    }

    public StartResult start(String firebaseUid, String methodStr) {
        var method = parseMethod(methodStr);
        if (method == null) return StartResult.badRequest("unsupported_method");

        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return StartResult.userNotProvisioned();
        long userId = u.get().id;

        repo.upsertMethod(userId, method.name());

        String devCode = null;
        String sessionId = null;
        String instructions = null;
        switch (method) {
            case email -> {
                var rateLimit = reserveEmailStartBudget(userId);
                if (rateLimit != null) return StartResult.rateLimited(rateLimit.error(), rateLimit.retryAfterSeconds());

                String code = generateCode6();
                String key = keyEmail(userId);
                redis.opsForValue().set(key, code, Duration.ofSeconds(props.getCodeTtlSeconds()));
                redis.delete(keyEmailAttempts(userId));
                if (!emailService.isEnabled()) {
                    if (!props.isEchoCode()) return StartResult.sendFailed();
                } else {
                    try {
                        if (u.get().email != null) {
                            emailService.sendUserVerificationEmail(u.get().email, code, props.getCodeTtlSeconds());
                        }
                    } catch (RuntimeException ex) {
                        return StartResult.sendFailed();
                    }
                }
                if (props.isEchoCode()) devCode = code;
                instructions = "Check your email for a 6-digit code and call finish with that code.";
            }
            case video -> {
                instructions = "Upload a short verification video via /v1/media/presign (video/mp4) and call finish with mediaKey.";
            }
            case thirdparty -> {
                sessionId = UUID.randomUUID().toString();
                String key = keyThirdParty(userId);
                redis.opsForValue().set(key, sessionId, Duration.ofMinutes(30));
                instructions = "Complete verification with the third-party provider using sessionId, then call finish with the provider token.";
            }
        }
        return StartResult.ok(method.name(), devCode, sessionId, instructions);
    }

    public FinishResult finish(String firebaseUid, String email, String methodStr, String code, String mediaKey, String token) {
        var method = parseMethod(methodStr);
        if (method == null) return FinishResult.badRequest("unsupported_method");

        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return FinishResult.userNotProvisioned();
        long userId = u.get().id;

        switch (method) {
            case email -> {
                if (code == null || code.isBlank()) return FinishResult.badRequest("code_required");
                String key = keyEmail(userId);
                String expected = redis.opsForValue().get(key);
                if (expected == null || !expected.equals(code)) {
                    if (expected == null) return FinishResult.invalidCode();
                    long attempts = incrementAttemptsWithTtl(keyEmailAttempts(userId), Duration.ofSeconds(props.getCodeTtlSeconds()));
                    if (attempts >= Math.max(1, props.getEmailCodeMaxAttempts())) {
                        clearEmailChallenge(userId);
                        return FinishResult.rateLimited("too_many_attempts", null);
                    }
                    return FinishResult.invalidCode();
                }
                redis.delete(key);
                redis.delete(keyEmailAttempts(userId));
            }
            case video -> {
                if (mediaKey == null || mediaKey.isBlank()) return FinishResult.badRequest("media_key_required");
            }
            case thirdparty -> {
                if (token == null || token.isBlank()) return FinishResult.badRequest("token_required");
                String sessKey = keyThirdParty(userId);
                String sessionId = redis.opsForValue().get(sessKey);
                if (sessionId == null) return FinishResult.badRequest("session_expired");
                boolean ok = thirdPartyVerifier.validate(sessionId, token);
                if (!ok) return FinishResult.badRequest("token_invalid");
                redis.delete(sessKey);
            }
        }
        String status = (method == Method.video) ? "pending" : "approved";
        long requestId = requests.insert(userId, email, method.name(), status, mediaKey, null);
        if ("approved".equals(status)) {
            repo.markVerified(userId, method.name());
            notifications.notifyUserVerificationApproved(userId, method.name(), requestId);
            if (method == Method.email && u.get().email != null) {
                emailService.sendUserVerifiedEmail(u.get().email);
            }
            return FinishResult.ok(true);
        }
        repo.markUnverified(userId, method.name());
        return FinishResult.ok(false);
    }

    private Method parseMethod(String m) {
        if (m == null) return null;
        try { return Method.valueOf(m.toLowerCase()); } catch (IllegalArgumentException ex) { return null; }
    }

    private String generateCode6() {
        int n = random.nextInt(900000) + 100000; // 100000..999999
        return Integer.toString(n);
    }

    private String keyEmail(long userId) { return "verify:email:" + userId; }
    private String keyEmailAttempts(long userId) { return "verify:email:attempts:" + userId; }
    private String keyEmailCooldown(long userId) { return "verify:email:cooldown:" + userId; }
    private String keyEmailStartsHour(long userId) { return "verify:email:start:hour:" + userId; }
    private String keyEmailStartsDay(long userId) { return "verify:email:start:day:" + userId; }
    private String keyThirdParty(long userId) { return "verify:thirdparty:" + userId; }

    private RateLimitResult reserveEmailStartBudget(long userId) {
        String cooldownKey = keyEmailCooldown(userId);
        String hourKey = keyEmailStartsHour(userId);
        String dayKey = keyEmailStartsDay(userId);

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

    private void clearEmailChallenge(long userId) {
        redis.delete(keyEmail(userId));
        redis.delete(keyEmailAttempts(userId));
    }

    private record RateLimitResult(String error, Integer retryAfterSeconds) {}

    public record StartResult(Status status, String method, String devCode, String sessionId, String instructions, String error, Integer retryAfterSeconds) {
        static StartResult ok(String method, String devCode, String sessionId, String instructions) {
            return new StartResult(Status.OK, method, devCode, sessionId, instructions, null, null);
        }
        static StartResult userNotProvisioned() { return new StartResult(Status.USER_NOT_PROVISIONED, null, null, null, null, null, null); }
        static StartResult badRequest(String err) { return new StartResult(Status.BAD_REQUEST, null, null, null, null, err, null); }
        static StartResult sendFailed() { return new StartResult(Status.SEND_FAILED, null, null, null, null, "email_send_failed", null); }
        static StartResult rateLimited(String err, Integer retryAfterSeconds) {
            return new StartResult(Status.RATE_LIMITED, null, null, null, null, normalizeError(err), retryAfterSeconds);
        }
    }

    private static String normalizeError(String err) {
        if (err == null || err.isBlank()) return "rate_limited";
        return err.trim().toLowerCase(Locale.ROOT);
    }

    public record FinishResult(Status status, Boolean verified, String error, Integer retryAfterSeconds) {
        static FinishResult ok(boolean verified) { return new FinishResult(Status.OK, verified, null, null); }
        static FinishResult userNotProvisioned() { return new FinishResult(Status.USER_NOT_PROVISIONED, null, null, null); }
        static FinishResult badRequest(String err) { return new FinishResult(Status.BAD_REQUEST, null, err, null); }
        static FinishResult invalidCode() { return new FinishResult(Status.INVALID_CODE, null, "invalid_code", null); }
        static FinishResult rateLimited(String err, Integer retryAfterSeconds) {
            return new FinishResult(Status.RATE_LIMITED, null, normalizeError(err), retryAfterSeconds);
        }
    }
}
