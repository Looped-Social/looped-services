package com.looped.media;

import com.looped.users.UserRepository;
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
import java.util.Map;

@RestController
@RequestMapping("/v1/media")
public class MediaController {
    private final MediaService mediaService;
    private final MediaRepository mediaRepository;
    private final UserRepository users;
    private final String cloudfrontDomain;
    private final String callbackSecret;

    public MediaController(MediaService mediaService, MediaRepository mediaRepository, UserRepository users,
                           @Value("${cloudfront.domain:}") String cloudfrontDomain,
                           @Value("${media.callbackSecret:}") String callbackSecret) {
        this.mediaService = mediaService;
        this.mediaRepository = mediaRepository;
        this.users = users;
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
            @Validated @RequestBody CallbackRequest body
    ) {
        // Verify signature if secret configured
        if (callbackSecret != null && !callbackSecret.isBlank()) {
            String expected = MediaService.hmacSha256Base64(callbackSecret, body.key());
            if (signature == null || !signature.equals(expected)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", "invalid_signature"
                ));
            }
        }
        // Verify user exists
        var u = users.findByFirebaseUid(jwt.getSubject());
        if (u.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
        }
        // Ensure key not already recorded
        if (mediaRepository.existsByKey(body.key())) {
            return ResponseEntity.ok(Map.of(
                    "status", "exists"
            ));
        }
        Long id = mediaRepository.insert(u.get().id, body.key(), body.mimeType(), body.width(), body.height(), body.durationSeconds());
        String cdnUrl = null;
        if (cloudfrontDomain != null && !cloudfrontDomain.isBlank()) {
            cdnUrl = "https://" + cloudfrontDomain + "/" + body.key();
        }
        Map<String,Object> out = new HashMap<>();
        out.put("id", id);
        out.put("key", body.key());
        out.put("mime_type", body.mimeType());
        if (cdnUrl != null) out.put("cdn_url", cdnUrl);
        return new ResponseEntity<>(out, HttpStatus.CREATED);
    }

    public record PresignRequest(@NotBlank String contentType, @NotNull Long sizeBytes) {}
    public record CallbackRequest(@NotBlank String key, @NotBlank String mimeType, Integer width, Integer height, Integer durationSeconds) {}
}

