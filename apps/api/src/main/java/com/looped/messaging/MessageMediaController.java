package com.looped.messaging;

import com.looped.users.UserRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/v1/message-media")
public class MessageMediaController {
    private final MessageMediaService media;
    private final MessageMediaRepository access;
    private final UserRepository users;

    public MessageMediaController(MessageMediaService media, MessageMediaRepository access, UserRepository users) {
        this.media = media;
        this.access = access;
        this.users = users;
    }

    @PostMapping("/presign")
    public ResponseEntity<?> presign(
            @AuthenticationPrincipal Jwt jwt,
            @Validated @RequestBody PresignRequest body
    ) {
        var actor = users.findByFirebaseUid(jwt.getSubject());
        if (actor.isEmpty() || actor.get().companyId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (actor.get().isAnonymous) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        var res = media.presignPut(body.contentType(), body.sizeBytes());
        if (res.status() == MessageMediaService.Status.BAD_REQUEST) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", res.error()));
        }
        Map<String, Object> out = new HashMap<>();
        out.put("key", res.key());
        out.put("uploadUrl", res.uploadUrl());
        out.put("headers", res.headers());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/resolve")
    public ResponseEntity<?> resolve(
            @AuthenticationPrincipal Jwt jwt,
            @Validated @RequestBody ResolveRequest body
    ) {
        var actor = users.findByFirebaseUid(jwt.getSubject());
        if (actor.isEmpty() || actor.get().companyId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (actor.get().isAnonymous) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        List<String> keys = body.keys().stream()
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .limit(50)
                .toList();

        List<Map<String, Object>> items = keys.stream().map(key -> {
            if (!key.startsWith("dm/")) return null;
            if (!access.userCanAccessMessageMedia(actor.get().id, actor.get().companyId, key)) return null;
            var presigned = media.presignGet(key);
            if (presigned.status() != MessageMediaService.Status.OK) return null;
            Map<String, Object> item = new HashMap<>();
            item.put("key", presigned.key());
            item.put("downloadUrl", presigned.downloadUrl());
            item.put("expires_in_seconds", presigned.expiresInSeconds());
            return item;
        }).filter(x -> x != null).toList();

        return ResponseEntity.ok(Map.of("items", items));
    }

    public record PresignRequest(@NotBlank String contentType, @NotNull Long sizeBytes) {}
    public record ResolveRequest(@NotEmpty List<String> keys) {}
}

