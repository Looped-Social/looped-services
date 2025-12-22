package com.looped.verification;

import com.looped.users.UserRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class VerificationService {
    public enum Method { email, video, thirdparty }
    public enum Status { OK, USER_NOT_PROVISIONED, BAD_REQUEST, INVALID_CODE }

    private final UserRepository users;
    private final VerificationRepository repo;
    private final VerificationRequestsRepository requests;
    private final StringRedisTemplate redis;
    private final VerificationProperties props;
    private final ThirdPartyVerifier thirdPartyVerifier;
    private final SecureRandom random = new SecureRandom();

    public VerificationService(UserRepository users, VerificationRepository repo, VerificationRequestsRepository requests,
                               StringRedisTemplate redis, VerificationProperties props, ThirdPartyVerifier thirdPartyVerifier) {
        this.users = users;
        this.repo = repo;
        this.requests = requests;
        this.redis = redis;
        this.props = props;
        this.thirdPartyVerifier = thirdPartyVerifier;
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
                String code = generateCode6();
                String key = keyEmail(userId);
                redis.opsForValue().set(key, code, Duration.ofSeconds(props.getCodeTtlSeconds()));
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
                if (expected == null || !expected.equals(code)) return FinishResult.invalidCode();
                redis.delete(key);
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
        requests.insert(userId, email, method.name(), status, mediaKey, null);
        if ("approved".equals(status)) {
            repo.markVerified(userId, method.name());
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
    private String keyThirdParty(long userId) { return "verify:thirdparty:" + userId; }

    public record StartResult(Status status, String method, String devCode, String sessionId, String instructions, String error) {
        static StartResult ok(String method, String devCode, String sessionId, String instructions) { return new StartResult(Status.OK, method, devCode, sessionId, instructions, null); }
        static StartResult userNotProvisioned() { return new StartResult(Status.USER_NOT_PROVISIONED, null, null, null, null, null); }
        static StartResult badRequest(String err) { return new StartResult(Status.BAD_REQUEST, null, null, null, null, err); }
    }

    public record FinishResult(Status status, Boolean verified, String error) {
        static FinishResult ok(boolean verified) { return new FinishResult(Status.OK, verified, null); }
        static FinishResult userNotProvisioned() { return new FinishResult(Status.USER_NOT_PROVISIONED, null, null); }
        static FinishResult badRequest(String err) { return new FinishResult(Status.BAD_REQUEST, null, err); }
        static FinishResult invalidCode() { return new FinishResult(Status.INVALID_CODE, null, "invalid_code"); }
    }
}
