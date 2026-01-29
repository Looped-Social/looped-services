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
        var existing = mediaRepository.findByKey(key);
        if (existing.isPresent()) {
            return ResponseEntity.ok(mediaPayload(existing.get(), cdnUrlFor(key), "exists"));
        }

        Long id = mediaRepository.insert(ownerId, key, normalizedMimeType, body.width(), body.height(), body.durationSeconds());
        String cdnUrl = cdnUrlFor(key);
        try {
            mediaModeration.moderateOnUpload(id, key, normalizedMimeType, cdnUrl);
        } catch (RuntimeException ignored) {}
        Map<String,Object> out = new HashMap<>(mediaPayload(id, key, normalizedMimeType, body.width(), body.height(), body.durationSeconds(), cdnUrl, "created"));
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
        List<Map<String, Object>> items = rows.stream()
                .filter(r -> r.s3Key != null && r.s3Key.startsWith("media/"))
                .filter(r -> r.removedAt == null)
                .filter(r -> (r.visibility != null && r.visibility.equalsIgnoreCase("public"))
                        || (requesterUserId != null && r.ownerId != null && r.ownerId.equals(requesterUserId)))
                .map(r -> {
                    String cdnUrl = null;
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
                    if (cloudfrontDomain != null && !cloudfrontDomain.isBlank()) {
                        cdnUrl = "https://" + cloudfrontDomain + "/" + r.s3Key;
                    } else {
                        cdnUrl = mediaService.presignedGetUrl(r.s3Key, java.time.Duration.ofMinutes(5));
                    }
                    item.put("cdn_url", cdnUrl);
                    item.put("cdnUrl", cdnUrl);
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
                                  @JsonAlias("duration_seconds") Integer durationSeconds) {}
    public record ResolveRequest(@NotEmpty List<Long> ids) {}

    private String cdnUrlFor(String key) {
        if (cloudfrontDomain != null && !cloudfrontDomain.isBlank()) {
            return "https://" + cloudfrontDomain + "/" + key;
        }
        return mediaService.presignedGetUrl(key, java.time.Duration.ofMinutes(5));
    }

    private Map<String, Object> mediaPayload(MediaRepository.MediaRow row, String cdnUrl, String status) {
        return mediaPayload(row.id, row.s3Key, row.mimeType, row.width, row.height, row.durationSeconds, cdnUrl, status);
    }

    private Map<String, Object> mediaPayload(Long id, String key, String mimeType, Integer width, Integer height, Integer durationSeconds, String cdnUrl, String status) {
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
        out.put("cdn_url", cdnUrl);
        out.put("cdnUrl", cdnUrl);
        return out;
    }
}
