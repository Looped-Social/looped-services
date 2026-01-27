package com.looped.verification;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.users.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class PhotoIdVerificationService {
    public enum Status { OK, USER_NOT_PROVISIONED, COMMUNITY_NOT_FOUND, BAD_REQUEST, FORBIDDEN, CONFLICT }

    public enum Kind { selfie, id_front, id_back }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final char[] NONCE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int NONCE_LEN = 8;

    private final UserRepository users;
    private final VerificationRepository verifications;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final VerificationRequestsRepository requests;
    private final StringRedisTemplate redis;
    private final PhotoIdVerificationProperties props;
    private final VerificationPrivateMediaService media;
    private final SecureRandom random = new SecureRandom();

    public PhotoIdVerificationService(
            UserRepository users,
            VerificationRepository verifications,
            CommunitiesRepository communities,
            CommunityVerificationsRepository communityVerifications,
            VerificationRequestsRepository requests,
            StringRedisTemplate redis,
            PhotoIdVerificationProperties props,
            VerificationPrivateMediaService media
    ) {
        this.users = users;
        this.verifications = verifications;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.requests = requests;
        this.redis = redis;
        this.props = props;
        this.media = media;
    }

    public StartResult start(String firebaseUid) {
        return start(firebaseUid, null);
    }

    public StartResult start(String firebaseUid, Long communityId) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return StartResult.userNotProvisioned();
        long userId = u.get().id;

        if (communityId != null) {
            if (u.get().companyId == null) return StartResult.userNotProvisioned();
            var community = communities.findById(communityId);
            if (community.isEmpty()) return StartResult.communityNotFound();
            if ("specialization".equalsIgnoreCase(community.get().kind)) {
                return StartResult.badRequest("verification_not_supported");
            }
            var current = communityVerifications.findForUserAndCommunity(userId, communityId).orElse(null);
            if (current != null && current.verified && (current.expiresAt == null || current.expiresAt.isAfter(java.time.OffsetDateTime.now()))) {
                return StartResult.alreadyVerified(current.method);
            }
        } else {
            var v = verifications.findByUserId(userId);
            if (v.isPresent() && v.get().verified) {
                return StartResult.alreadyVerified(v.get().method);
            }
        }
        if (requests.existsPendingForUserAndMethodAndCommunityId(userId, "photo_id", communityId)) {
            return StartResult.conflict("already_pending");
        }

        String sessionId = UUID.randomUUID().toString();
        String nonce = generateNonce();
        redis.opsForValue().set(keyActiveSession(userId, communityId), sessionId, props.getSessionTtl());
        redis.opsForValue().set(keyNonce(userId, communityId), sessionId + ":" + nonce, props.getSessionTtl());
        return StartResult.ok(sessionId, nonce, props.getMaxImageBytes());
    }

    public PresignResult presign(String firebaseUid, String sessionId, String kindRaw, String contentType, long sizeBytes) {
        return presign(firebaseUid, null, sessionId, kindRaw, contentType, sizeBytes);
    }

    public PresignResult presign(String firebaseUid, Long communityId, String sessionId, String kindRaw, String contentType, long sizeBytes) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return PresignResult.userNotProvisioned();
        long userId = u.get().id;

        if (communityId != null) {
            if (u.get().companyId == null) return PresignResult.userNotProvisioned();
            var community = communities.findById(communityId);
            if (community.isEmpty()) return PresignResult.communityNotFound();
            if ("specialization".equalsIgnoreCase(community.get().kind)) {
                return PresignResult.badRequest("verification_not_supported");
            }
        }

        if (!isActiveSession(userId, communityId, sessionId)) return PresignResult.forbidden("invalid_session");
        Kind kind = parseKind(kindRaw);
        if (kind == null) return PresignResult.badRequest("invalid_kind");

        String key = buildKey(userId, sessionId, kind, contentType);
        if (key == null) return PresignResult.badRequest("unsupported_content_type");

        var res = media.presignPutImage(key, contentType, sizeBytes);
        if (res.status() != VerificationPrivateMediaService.Status.OK) {
            return PresignResult.badRequest(res.error());
        }
        return PresignResult.ok(kind.name(), res.key(), res.uploadUrl(), res.headers());
    }

    public SubmitResult submit(String firebaseUid, String email, String sessionId, String selfieKey, String idFrontKey, String idBackKey) {
        return submit(firebaseUid, null, email, sessionId, selfieKey, idFrontKey, idBackKey);
    }

    public SubmitResult submit(String firebaseUid, Long communityId, String email, String sessionId, String selfieKey, String idFrontKey, String idBackKey) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return SubmitResult.userNotProvisioned();
        long userId = u.get().id;

        if (communityId != null) {
            if (u.get().companyId == null) return SubmitResult.userNotProvisioned();
            var community = communities.findById(communityId);
            if (community.isEmpty()) return SubmitResult.communityNotFound();
            if ("specialization".equalsIgnoreCase(community.get().kind)) {
                return SubmitResult.badRequest("verification_not_supported");
            }
        }

        if (!isActiveSession(userId, communityId, sessionId)) return SubmitResult.forbidden("invalid_session");
        if (requests.existsPendingForUserAndMethodAndCommunityId(userId, "photo_id", communityId)) {
            return SubmitResult.conflict("already_pending");
        }
        if (selfieKey == null || selfieKey.isBlank()) return SubmitResult.badRequest("selfie_key_required");
        if (idFrontKey == null || idFrontKey.isBlank()) return SubmitResult.badRequest("id_front_key_required");

        if (!isExpectedKey(userId, sessionId, Kind.selfie, selfieKey)) return SubmitResult.badRequest("selfie_key_invalid");
        if (!isExpectedKey(userId, sessionId, Kind.id_front, idFrontKey)) return SubmitResult.badRequest("id_front_key_invalid");
        if (idBackKey != null && !idBackKey.isBlank() && !isExpectedKey(userId, sessionId, Kind.id_back, idBackKey)) {
            return SubmitResult.badRequest("id_back_key_invalid");
        }

        String nonce = getNonceForSession(userId, communityId, sessionId);
        if (nonce == null) return SubmitResult.forbidden("invalid_session");

        redis.delete(keyActiveSession(userId, communityId));
        redis.delete(keyNonce(userId, communityId));

        String metadata = toJsonQuietly(java.util.Map.of("nonce", nonce));
        long requestId = requests.insertPhotoId(userId, communityId, email, "pending", selfieKey, idFrontKey, emptyToNull(idBackKey), metadata);
        return SubmitResult.ok(requestId);
    }

    public StatusResult status(String firebaseUid) {
        return status(firebaseUid, null);
    }

    public StatusResult status(String firebaseUid, Long communityId) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return StatusResult.userNotProvisioned();
        long userId = u.get().id;

        if (communityId != null) {
            if (u.get().companyId == null) return StatusResult.userNotProvisioned();
            var community = communities.findById(communityId);
            if (community.isEmpty()) return StatusResult.communityNotFound();
            if ("specialization".equalsIgnoreCase(community.get().kind)) {
                return StatusResult.badRequest("verification_not_supported");
            }
            var current = communityVerifications.findForUserAndCommunity(userId, communityId).orElse(null);
            if (current != null && current.verified && (current.expiresAt == null || current.expiresAt.isAfter(java.time.OffsetDateTime.now()))) {
                return StatusResult.ok("approved", current.method, current.verifiedAt, current.expiresAt);
            }
        } else {
            var v = verifications.findByUserId(userId).orElse(null);
            if (v != null && v.verified) {
                return StatusResult.ok("approved", v.method, v.verifiedAt, null);
            }
        }
        var latest = requests.findLatestForUserAndMethodAndCommunityId(userId, "photo_id", communityId).orElse(null);
        if (latest == null) {
            return StatusResult.ok("none", "photo_id", null, null);
        }
        String normalized = latest.status != null ? latest.status.toLowerCase(Locale.ROOT) : "pending";
        String status = switch (normalized) {
            case "approved" -> "approved";
            case "rejected" -> "rejected";
            default -> "pending_review";
        };
        return StatusResult.ok(status, "photo_id", null, null);
    }

    private boolean isActiveSession(long userId, Long communityId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        String active = redis.opsForValue().get(keyActiveSession(userId, communityId));
        return active != null && active.equals(sessionId);
    }

    private String keyActiveSession(long userId, Long communityId) {
        if (communityId == null) {
            return "verify:photo_id:session:" + userId;
        }
        return "verify:photo_id:session:" + userId + ":" + communityId;
    }

    private String keyNonce(long userId, Long communityId) {
        if (communityId == null) {
            return "verify:photo_id:nonce:" + userId;
        }
        return "verify:photo_id:nonce:" + userId + ":" + communityId;
    }

    private String getNonceForSession(long userId, Long communityId, String sessionId) {
        String raw = redis.opsForValue().get(keyNonce(userId, communityId));
        if (raw == null || raw.isBlank()) return null;
        int idx = raw.indexOf(':');
        if (idx <= 0 || idx >= raw.length() - 1) return null;
        String storedSession = raw.substring(0, idx);
        if (!storedSession.equals(sessionId)) return null;
        return raw.substring(idx + 1);
    }

    private String generateNonce() {
        char[] out = new char[NONCE_LEN];
        for (int i = 0; i < NONCE_LEN; i++) {
            out[i] = NONCE_CHARS[random.nextInt(NONCE_CHARS.length)];
        }
        return new String(out);
    }

    private String toJsonQuietly(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private Kind parseKind(String raw) {
        if (raw == null) return null;
        try {
            return Kind.valueOf(raw.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String buildKey(long userId, String sessionId, Kind kind, String contentType) {
        String ext = extForContentType(contentType);
        if (ext == null) return null;
        return "verification/photo-id/" + userId + "/" + sessionId + "/" + kind.name() + ext;
    }

    private String extForContentType(String contentType) {
        if (contentType == null) return null;
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> null;
        };
    }

    private boolean isExpectedKey(long userId, String sessionId, Kind kind, String key) {
        if (key == null) return false;
        String prefix = "verification/photo-id/" + userId + "/" + sessionId + "/";
        if (!key.startsWith(prefix)) return false;
        String file = key.substring(prefix.length());
        if (!file.startsWith(kind.name())) return false;
        return file.equals(kind.name() + ".jpg") || file.equals(kind.name() + ".png");
    }

    private String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isBlank() ? null : t;
    }

    public record StartResult(Status status, String uploadSessionId, String nonce, Long maxBytes, String error, String currentMethod) {
        static StartResult ok(String sessionId, String nonce, long maxBytes) { return new StartResult(Status.OK, sessionId, nonce, maxBytes, null, null); }
        static StartResult userNotProvisioned() { return new StartResult(Status.USER_NOT_PROVISIONED, null, null, null, null, null); }
        static StartResult communityNotFound() { return new StartResult(Status.COMMUNITY_NOT_FOUND, null, null, null, "community_not_found", null); }
        static StartResult conflict(String err) { return new StartResult(Status.CONFLICT, null, null, null, err, null); }
        static StartResult alreadyVerified(String method) { return new StartResult(Status.CONFLICT, null, null, null, "already_verified", method); }
        static StartResult badRequest(String err) { return new StartResult(Status.BAD_REQUEST, null, null, null, err, null); }
    }

    public record PresignResult(Status status, String kind, String key, String uploadUrl, java.util.Map<String, String> headers, String error) {
        static PresignResult ok(String kind, String key, String uploadUrl, java.util.Map<String, String> headers) {
            return new PresignResult(Status.OK, kind, key, uploadUrl, headers, null);
        }
        static PresignResult userNotProvisioned() { return new PresignResult(Status.USER_NOT_PROVISIONED, null, null, null, null, null); }
        static PresignResult communityNotFound() { return new PresignResult(Status.COMMUNITY_NOT_FOUND, null, null, null, null, "community_not_found"); }
        static PresignResult forbidden(String err) { return new PresignResult(Status.FORBIDDEN, null, null, null, null, err); }
        static PresignResult badRequest(String err) { return new PresignResult(Status.BAD_REQUEST, null, null, null, null, err); }
    }

    public record SubmitResult(Status status, Long verificationRequestId, String error) {
        static SubmitResult ok(long id) { return new SubmitResult(Status.OK, id, null); }
        static SubmitResult userNotProvisioned() { return new SubmitResult(Status.USER_NOT_PROVISIONED, null, null); }
        static SubmitResult communityNotFound() { return new SubmitResult(Status.COMMUNITY_NOT_FOUND, null, "community_not_found"); }
        static SubmitResult forbidden(String err) { return new SubmitResult(Status.FORBIDDEN, null, err); }
        static SubmitResult badRequest(String err) { return new SubmitResult(Status.BAD_REQUEST, null, err); }
        static SubmitResult conflict(String err) { return new SubmitResult(Status.CONFLICT, null, err); }
    }

    public record StatusResult(Status status, String state, String method, java.time.OffsetDateTime verifiedAt, java.time.OffsetDateTime expiresAt, String error) {
        static StatusResult ok(String state, String method, java.time.OffsetDateTime verifiedAt, java.time.OffsetDateTime expiresAt) {
            return new StatusResult(Status.OK, state, method, verifiedAt, expiresAt, null);
        }
        static StatusResult userNotProvisioned() { return new StatusResult(Status.USER_NOT_PROVISIONED, null, null, null, null, null); }
        static StatusResult communityNotFound() { return new StatusResult(Status.COMMUNITY_NOT_FOUND, null, null, null, null, "community_not_found"); }
        static StatusResult badRequest(String err) { return new StatusResult(Status.BAD_REQUEST, null, null, null, null, err); }
    }
}
