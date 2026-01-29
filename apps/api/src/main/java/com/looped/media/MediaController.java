package com.looped.media;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.looped.users.UserRepository;
import com.looped.moderation.MediaModerationService;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/v1/media")
public class MediaController {
    private final MediaService mediaService;
    private final MediaRepository mediaRepository;
    private final UserRepository users;
    private final MediaModerationService mediaModeration;
    private final String cloudfrontDomain;
    private final String callbackSecret;

    public MediaController(MediaService mediaService, MediaRepository mediaRepository, UserRepository users,
                           MediaModerationService mediaModeration,
                           @Value("${cloudfront.domain:}") String cloudfrontDomain,
                           @Value("${media.callbackSecret:}") String callbackSecret) {
        this.mediaService = mediaService;
        this.mediaRepository = mediaRepository;
        this.users = users;
        this.mediaModeration = mediaModeration;
        this.cloudfrontDomain = cloudfrontDomain;
        this.callbackSecret = callbackSecret;
    }

    @PostMapping("/presign")
    public ResponseEntity<?> presign(@Validated @RequestBody PresignRequest body) {
        var res = mediaService.presign(body.contentType(), body.sizeBytes());
        if (res.status() == MediaService.Status.BAD_REQUEST) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", res.error()
            ));
        }
        Map<String,Object> out = new HashMap<>();
        out.put("key", res.key());
        out.put("uploadUrl", res.uploadUrl());
        out.put("headers", res.headers());
        if (res.callbackSignature() != null) out.put("callbackSignature", res.callbackSignature());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/callback")
    public ResponseEntity<?> callback(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Media-Signature", required = false) String signature,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @Validated @RequestBody CallbackRequest body
    ) {
        String key = body.key();
        if (key == null || !key.startsWith("media/")) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_key",
                    "message", "key must start with media/"
            ));
        }
        String normalizedMimeType = MediaService.normalizeMimeType(body.mimeType());
        if (normalizedMimeType == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_mime_type",
                    "message", "mimeType must be a valid MIME type"
            ));
        }

        // Verify signature if secret configured
        if (callbackSecret != null && !callbackSecret.isBlank()) {
            String expected = MediaService.hmacSha256Base64(callbackSecret, key);
            if (signature == null || !signature.equals(expected)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", "invalid_signature"
                ));
            }
        }
        Long ownerId = null;
        boolean isAnon = actor != null && actor.equalsIgnoreCase("anon");
        if (!isAnon) {
            if (jwt == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "error", "unauthorized",
                        "message", "Authorization is required"
                ));
            }
            var u = users.findByFirebaseUid(jwt.getSubject());
            if (u.isEmpty()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "user_not_provisioned"
                ));
            }
            ownerId = u.get().id;
        }

        Long thumbnailMediaAssetId = null;
        if (body.thumbnailMediaAssetId() != null && body.thumbnailMediaAssetId() > 0) {
            thumbnailMediaAssetId = body.thumbnailMediaAssetId();
        }
        boolean isVideo = normalizedMimeType.toLowerCase(Locale.ROOT).startsWith("video/");
        if (thumbnailMediaAssetId != null && !isVideo) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "thumbnail_invalid",
                    "message", "thumbnailMediaAssetId is only supported for video/* assets"
            ));
        }
        if (thumbnailMediaAssetId != null) {
            var thumb = mediaRepository.findById(thumbnailMediaAssetId).orElse(null);
            if (thumb == null || thumb.removedAt != null || thumb.s3Key == null || !thumb.s3Key.startsWith("media/")) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "thumbnail_not_found",
                        "message", "thumbnailMediaAssetId not found"
                ));
            }
            String thumbMt = thumb.mimeType == null ? null : thumb.mimeType.toLowerCase(Locale.ROOT);
            if (thumbMt == null || !thumbMt.startsWith("image/")) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "thumbnail_invalid",
                        "message", "thumbnailMediaAssetId must refer to an image/* asset"
                ));
            }
            if (isAnon) {
                if (thumb.ownerId != null) {
                    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                            "error", "thumbnail_forbidden",
                            "message", "Anonymous thumbnails must not be user-owned"
                    ));
                }
            } else if (ownerId != null) {
                if (thumb.ownerId == null || !thumb.ownerId.equals(ownerId)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                            "error", "thumbnail_forbidden",
                            "message", "You may only use thumbnails you uploaded"
                    ));
                }
            }
        }

        var existing = mediaRepository.findByKey(key);
        if (existing.isPresent()) {
            return ResponseEntity.ok(mediaPayload(existing.get(), cdnUrlFor(key), "exists"));
        }

        Long id = mediaRepository.insert(ownerId, key, normalizedMimeType, body.width(), body.height(), body.durationSeconds(), thumbnailMediaAssetId);
        String cdnUrl = cdnUrlFor(key);
        try {
            mediaModeration.moderateOnUpload(id, key, normalizedMimeType, cdnUrl);
        } catch (RuntimeException ignored) {}
        Map<String,Object> out = new HashMap<>(mediaPayload(id, key, normalizedMimeType, body.width(), body.height(), body.durationSeconds(), thumbnailMediaAssetId, cdnUrl, "created"));
        return new ResponseEntity<>(out, HttpStatus.CREATED);
    }

    @PostMapping("/resolve")
    public ResponseEntity<?> resolve(@AuthenticationPrincipal Jwt jwt, @Validated @RequestBody ResolveRequest body) {
        final Long requesterUserId = jwt == null
                ? null
                : users.findByFirebaseUid(jwt.getSubject()).map(u -> u.id).orElse(null);
        List<Long> ids = body.ids().stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .limit(50)
                .toList();

        var rows = mediaRepository.findByIds(ids);

        java.util.Set<Long> thumbnailIds = rows.stream()
                .map(r -> r.thumbnailMediaAssetId)
                .filter(id -> id != null && id > 0)
                .collect(java.util.stream.Collectors.toSet());

        final java.util.Map<Long, MediaRepository.MediaRow> thumbnailsById;
        if (thumbnailIds.isEmpty()) {
            thumbnailsById = java.util.Map.of();
        } else {
            var thumbRows = mediaRepository.findByIds(thumbnailIds.stream().toList());
            java.util.Map<Long, MediaRepository.MediaRow> tmp = new java.util.HashMap<>();
            for (var t : thumbRows) {
                if (t == null) continue;
                if (t.s3Key == null || !t.s3Key.startsWith("media/")) continue;
                if (t.removedAt != null) continue;
                boolean visible = (t.visibility != null && t.visibility.equalsIgnoreCase("public"))
                        || (requesterUserId != null && t.ownerId != null && t.ownerId.equals(requesterUserId));
                if (!visible) continue;
                tmp.put(t.id, t);
            }
            thumbnailsById = tmp;
        }

        List<Map<String, Object>> items = rows.stream()
                .filter(r -> r.s3Key != null && r.s3Key.startsWith("media/"))
                .filter(r -> r.removedAt == null)
                .filter(r -> (r.visibility != null && r.visibility.equalsIgnoreCase("public"))
                        || (requesterUserId != null && r.ownerId != null && r.ownerId.equals(requesterUserId)))
                .map(r -> {
                    CdnUrl cdn = cdnUrl(r.s3Key);
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", r.id);
                    item.put("key", r.s3Key);
                    item.put("mime_type", r.mimeType);
                    item.put("mimeType", r.mimeType);
                    if (r.width != null) {
                        item.put("width", r.width);
                    }
                    if (r.height != null) {
                        item.put("height", r.height);
                    }
                    if (r.durationSeconds != null) {
                        item.put("duration_seconds", r.durationSeconds);
                        item.put("durationSeconds", r.durationSeconds);
                    }
                    item.put("cdn_url", cdn.url());
                    item.put("cdnUrl", cdn.url());
                    if (cdn.expiresAt() != null && cdn.ttlSeconds() != null) {
                        item.put("expires_at", cdn.expiresAt());
                        item.put("expiresAt", cdn.expiresAt());
                        item.put("ttl_seconds", cdn.ttlSeconds());
                        item.put("ttlSeconds", cdn.ttlSeconds());
                    }

                    boolean isVideo = r.mimeType != null && r.mimeType.toLowerCase(Locale.ROOT).startsWith("video/");
                    if (isVideo && r.thumbnailMediaAssetId != null) {
                        var thumb = thumbnailsById.get(r.thumbnailMediaAssetId);
                        if (thumb != null) {
                            CdnUrl thumbCdn = cdnUrl(thumb.s3Key);
                            item.put("thumbnail_media_asset_id", thumb.id);
                            item.put("thumbnailMediaAssetId", thumb.id);
                            item.put("thumbnail_url", thumbCdn.url());
                            item.put("thumbnailUrl", thumbCdn.url());
                            if (thumbCdn.expiresAt() != null && thumbCdn.ttlSeconds() != null) {
                                item.put("thumbnail_expires_at", thumbCdn.expiresAt());
                                item.put("thumbnailExpiresAt", thumbCdn.expiresAt());
                                item.put("thumbnail_ttl_seconds", thumbCdn.ttlSeconds());
                                item.put("thumbnailTtlSeconds", thumbCdn.ttlSeconds());
                            }
                        }
                    }
                    return item;
                })
                .toList();

        return ResponseEntity.ok(Map.of("items", items));
    }

    public record PresignRequest(@NotBlank @JsonAlias("content_type") String contentType, @NotNull @JsonAlias("size_bytes") Long sizeBytes) {}
    public record CallbackRequest(@NotBlank String key,
                                  @NotBlank @JsonAlias("mime_type") String mimeType,
                                  Integer width,
                                  Integer height,
                                  @JsonAlias("duration_seconds") Integer durationSeconds,
                                  @JsonAlias("thumbnail_media_asset_id") Long thumbnailMediaAssetId) {}
    public record ResolveRequest(@NotEmpty List<Long> ids) {}

    private String cdnUrlFor(String key) {
        if (cloudfrontDomain != null && !cloudfrontDomain.isBlank()) {
            return "https://" + cloudfrontDomain + "/" + key;
        }
        return mediaService.presignedGetUrl(key, java.time.Duration.ofMinutes(5));
    }

    private Map<String, Object> mediaPayload(MediaRepository.MediaRow row, String cdnUrl, String status) {
        return mediaPayload(row.id, row.s3Key, row.mimeType, row.width, row.height, row.durationSeconds, row.thumbnailMediaAssetId, cdnUrl, status);
    }

    private Map<String, Object> mediaPayload(Long id, String key, String mimeType, Integer width, Integer height, Integer durationSeconds, Long thumbnailMediaAssetId, String cdnUrl, String status) {
        Map<String, Object> out = new HashMap<>();
        if (status != null) out.put("status", status);
        out.put("id", id);
        out.put("key", key);
        out.put("mime_type", mimeType);
        out.put("mimeType", mimeType);
        if (width != null) out.put("width", width);
        if (height != null) out.put("height", height);
        if (durationSeconds != null) {
            out.put("duration_seconds", durationSeconds);
            out.put("durationSeconds", durationSeconds);
        }
        if (thumbnailMediaAssetId != null) {
            out.put("thumbnail_media_asset_id", thumbnailMediaAssetId);
            out.put("thumbnailMediaAssetId", thumbnailMediaAssetId);
        }
        out.put("cdn_url", cdnUrl);
        out.put("cdnUrl", cdnUrl);
        return out;
    }

    private record CdnUrl(String url, java.time.Instant expiresAt, Integer ttlSeconds) {}

    private CdnUrl cdnUrl(String key) {
        if (cloudfrontDomain != null && !cloudfrontDomain.isBlank()) {
            return new CdnUrl("https://" + cloudfrontDomain + "/" + key, null, null);
        }
        int ttlSeconds = 300;
        var ttl = java.time.Duration.ofSeconds(ttlSeconds);
        String url = mediaService.presignedGetUrl(key, ttl);
        return new CdnUrl(url, java.time.Instant.now().plus(ttl), ttlSeconds);
    }
}
