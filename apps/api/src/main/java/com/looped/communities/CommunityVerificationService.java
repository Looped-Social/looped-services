package com.looped.communities;

import com.looped.verification.ThirdPartyVerifier;
import com.looped.verification.VerificationProperties;
import com.looped.verification.VerificationRequestsRepository;
import com.looped.users.UserRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class CommunityVerificationService {
    public enum Method { email, video, thirdparty }
    public enum Status { OK, USER_NOT_PROVISIONED, COMMUNITY_NOT_FOUND, BAD_REQUEST, INVALID_CODE }

    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final VerificationRequestsRepository requests;
    private final StringRedisTemplate redis;
    private final VerificationProperties props;
    private final ThirdPartyVerifier thirdPartyVerifier;
    private final SecureRandom random = new SecureRandom();

    public CommunityVerificationService(UserRepository users,
                                        CommunitiesRepository communities,
                                        CommunityVerificationsRepository communityVerifications,
                                        VerificationRequestsRepository requests,
                                        StringRedisTemplate redis,
                                        VerificationProperties props,
                                        ThirdPartyVerifier thirdPartyVerifier) {
        this.users = users;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.requests = requests;
        this.redis = redis;
        this.props = props;
        this.thirdPartyVerifier = thirdPartyVerifier;
    }

    public StartResult start(String firebaseUid, long communityId, String methodStr) {
        var method = parseMethod(methodStr);
        if (method == null) return StartResult.badRequest("unsupported_method");

        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty() || u.get().companyId == null) return StartResult.userNotProvisioned();
        if (communities.findById(communityId).isEmpty()) return StartResult.communityNotFound();

        String devCode = null;
        String sessionId = null;
        String instructions = null;
        switch (method) {
            case email -> {
                String code = generateCode6();
                String key = keyEmail(u.get().id, communityId);
                redis.opsForValue().set(key, code, Duration.ofSeconds(props.getCodeTtlSeconds()));
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

    public FinishResult finish(String firebaseUid, long communityId, String email, String methodStr,
                               String code, String mediaKey, String token) {
        var method = parseMethod(methodStr);
        if (method == null) return FinishResult.badRequest("unsupported_method");

        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty() || u.get().companyId == null) return FinishResult.userNotProvisioned();
        var community = communities.findById(communityId);
        if (community.isEmpty()) return FinishResult.communityNotFound();

        switch (method) {
            case email -> {
                if (code == null || code.isBlank()) return FinishResult.badRequest("code_required");
                String key = keyEmail(u.get().id, communityId);
                String expected = redis.opsForValue().get(key);
                if (expected == null || !expected.equals(code)) return FinishResult.invalidCode();
                redis.delete(key);
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
        requests.insert(u.get().id, communityId, email, method.name(), status, mediaKey, null);
        if ("approved".equals(status)) {
            OffsetDateTime expiresAt = resolveExpiry(community.get());
            communityVerifications.markVerified(u.get().id, communityId, method.name(), expiresAt);
            return FinishResult.ok(true, expiresAt);
        }
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
        if (ttlDays != null && ttlDays > 0) {
            return OffsetDateTime.now().plusDays(ttlDays);
        }
        return null;
    }

    private String keyEmail(long userId, long communityId) { return "verify:community:email:" + userId + ":" + communityId; }
    private String keyThirdParty(long userId, long communityId) { return "verify:community:thirdparty:" + userId + ":" + communityId; }

    public record StartResult(Status status, String method, String devCode, String sessionId, String instructions, String error) {
        static StartResult ok(String method, String devCode, String sessionId, String instructions) {
            return new StartResult(Status.OK, method, devCode, sessionId, instructions, null);
        }
        static StartResult userNotProvisioned() { return new StartResult(Status.USER_NOT_PROVISIONED, null, null, null, null, null); }
        static StartResult communityNotFound() { return new StartResult(Status.COMMUNITY_NOT_FOUND, null, null, null, null, null); }
        static StartResult badRequest(String err) { return new StartResult(Status.BAD_REQUEST, null, null, null, null, err); }
    }

    public record FinishResult(Status status, Boolean verified, OffsetDateTime expiresAt, String error) {
        static FinishResult ok(boolean verified, OffsetDateTime expiresAt) { return new FinishResult(Status.OK, verified, expiresAt, null); }
        static FinishResult userNotProvisioned() { return new FinishResult(Status.USER_NOT_PROVISIONED, null, null, null); }
        static FinishResult communityNotFound() { return new FinishResult(Status.COMMUNITY_NOT_FOUND, null, null, null); }
        static FinishResult badRequest(String err) { return new FinishResult(Status.BAD_REQUEST, null, null, err); }
        static FinishResult invalidCode() { return new FinishResult(Status.INVALID_CODE, null, null, "invalid_code"); }
    }
}
