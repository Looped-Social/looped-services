package com.looped.verification;

import com.looped.users.UserRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class PhotoIdVerificationService {
    public enum Status { OK, USER_NOT_PROVISIONED, BAD_REQUEST, FORBIDDEN, CONFLICT }

    public enum Kind { selfie, id_front, id_back }

    private final UserRepository users;
    private final VerificationRepository verifications;
    private final VerificationRequestsRepository requests;
    private final StringRedisTemplate redis;
    private final PhotoIdVerificationProperties props;
    private final VerificationPrivateMediaService media;

    public PhotoIdVerificationService(
            UserRepository users,
            VerificationRepository verifications,
            VerificationRequestsRepository requests,
            StringRedisTemplate redis,
            PhotoIdVerificationProperties props,
            VerificationPrivateMediaService media
    ) {
        this.users = users;
        this.verifications = verifications;
        this.requests = requests;
        this.redis = redis;
        this.props = props;
        this.media = media;
    }

    public StartResult start(String firebaseUid) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return StartResult.userNotProvisioned();
        long userId = u.get().id;

        var v = verifications.findByUserId(userId);
        if (v.isPresent() && v.get().verified) {
            return StartResult.alreadyVerified(v.get().method);
        }
        if (requests.existsPendingForUserAndMethod(userId, "photo_id")) {
            return StartResult.conflict("already_pending");
        }

        String sessionId = UUID.randomUUID().toString();
        redis.opsForValue().set(keyActiveSession(userId), sessionId, props.getSessionTtl());
        return StartResult.ok(sessionId, props.getMaxImageBytes());
    }

    public PresignResult presign(String firebaseUid, String sessionId, String kindRaw, String contentType, long sizeBytes) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return PresignResult.userNotProvisioned();
        long userId = u.get().id;

        if (!isActiveSession(userId, sessionId)) return PresignResult.forbidden("invalid_session");
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
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return SubmitResult.userNotProvisioned();
        long userId = u.get().id;

        if (!isActiveSession(userId, sessionId)) return SubmitResult.forbidden("invalid_session");
        if (requests.existsPendingForUserAndMethod(userId, "photo_id")) {
            return SubmitResult.conflict("already_pending");
        }
        if (selfieKey == null || selfieKey.isBlank()) return SubmitResult.badRequest("selfie_key_required");
        if (idFrontKey == null || idFrontKey.isBlank()) return SubmitResult.badRequest("id_front_key_required");

        if (!isExpectedKey(userId, sessionId, Kind.selfie, selfieKey)) return SubmitResult.badRequest("selfie_key_invalid");
        if (!isExpectedKey(userId, sessionId, Kind.id_front, idFrontKey)) return SubmitResult.badRequest("id_front_key_invalid");
        if (idBackKey != null && !idBackKey.isBlank() && !isExpectedKey(userId, sessionId, Kind.id_back, idBackKey)) {
            return SubmitResult.badRequest("id_back_key_invalid");
        }

        redis.delete(keyActiveSession(userId));

        long requestId = requests.insertPhotoId(userId, email, "pending", selfieKey, idFrontKey, emptyToNull(idBackKey));
        return SubmitResult.ok(requestId);
    }

    public StatusResult status(String firebaseUid) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return StatusResult.userNotProvisioned();
        long userId = u.get().id;

        var v = verifications.findByUserId(userId).orElse(null);
        if (v != null && v.verified) {
            return StatusResult.ok("approved", v.method, v.verifiedAt);
        }
        var latest = requests.findLatestForUserAndMethod(userId, "photo_id").orElse(null);
        if (latest == null) {
            return StatusResult.ok("none", "photo_id", null);
        }
        String normalized = latest.status != null ? latest.status.toLowerCase(Locale.ROOT) : "pending";
        String status = switch (normalized) {
            case "approved" -> "approved";
            case "rejected" -> "rejected";
            default -> "pending_review";
        };
        return StatusResult.ok(status, "photo_id", null);
    }

    private boolean isActiveSession(long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        String active = redis.opsForValue().get(keyActiveSession(userId));
        return active != null && active.equals(sessionId);
    }

    private String keyActiveSession(long userId) {
        return "verify:photo_id:session:" + userId;
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

    public record StartResult(Status status, String uploadSessionId, Long maxBytes, String error, String currentMethod) {
        static StartResult ok(String sessionId, long maxBytes) { return new StartResult(Status.OK, sessionId, maxBytes, null, null); }
        static StartResult userNotProvisioned() { return new StartResult(Status.USER_NOT_PROVISIONED, null, null, null, null); }
        static StartResult conflict(String err) { return new StartResult(Status.CONFLICT, null, null, err, null); }
        static StartResult alreadyVerified(String method) { return new StartResult(Status.CONFLICT, null, null, "already_verified", method); }
    }

    public record PresignResult(Status status, String kind, String key, String uploadUrl, java.util.Map<String, String> headers, String error) {
        static PresignResult ok(String kind, String key, String uploadUrl, java.util.Map<String, String> headers) {
            return new PresignResult(Status.OK, kind, key, uploadUrl, headers, null);
        }
        static PresignResult userNotProvisioned() { return new PresignResult(Status.USER_NOT_PROVISIONED, null, null, null, null, null); }
        static PresignResult forbidden(String err) { return new PresignResult(Status.FORBIDDEN, null, null, null, null, err); }
        static PresignResult badRequest(String err) { return new PresignResult(Status.BAD_REQUEST, null, null, null, null, err); }
    }

    public record SubmitResult(Status status, Long verificationRequestId, String error) {
        static SubmitResult ok(long id) { return new SubmitResult(Status.OK, id, null); }
        static SubmitResult userNotProvisioned() { return new SubmitResult(Status.USER_NOT_PROVISIONED, null, null); }
        static SubmitResult forbidden(String err) { return new SubmitResult(Status.FORBIDDEN, null, err); }
        static SubmitResult badRequest(String err) { return new SubmitResult(Status.BAD_REQUEST, null, err); }
        static SubmitResult conflict(String err) { return new SubmitResult(Status.CONFLICT, null, err); }
    }

    public record StatusResult(Status status, String state, String method, java.time.OffsetDateTime verifiedAt) {
        static StatusResult ok(String state, String method, java.time.OffsetDateTime verifiedAt) {
            return new StatusResult(Status.OK, state, method, verifiedAt);
        }
        static StatusResult userNotProvisioned() { return new StatusResult(Status.USER_NOT_PROVISIONED, null, null, null); }
    }
}
