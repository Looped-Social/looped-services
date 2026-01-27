package com.looped.communities;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.looped.verification.PhotoIdVerificationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/communities/{communityId}/verification/photo-id")
public class CommunityPhotoIdVerificationController {
    private final PhotoIdVerificationService service;

    public CommunityPhotoIdVerificationController(PhotoIdVerificationService service) {
        this.service = service;
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@AuthenticationPrincipal Jwt jwt, @PathVariable("communityId") long communityId) {
        var res = service.start(jwt.getSubject(), communityId);
        if (res.status() == PhotoIdVerificationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == PhotoIdVerificationService.Status.COMMUNITY_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        if (res.status() == PhotoIdVerificationService.Status.BAD_REQUEST) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", res.error()));
        }
        if (res.status() == PhotoIdVerificationService.Status.CONFLICT) {
            Map<String, Object> out = new HashMap<>();
            out.put("error", res.error());
            if (res.currentMethod() != null) out.put("current_method", res.currentMethod());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(out);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("status", "pending_upload");
        out.put("method", "photo_id");
        out.put("upload_session_id", res.uploadSessionId());
        out.put("nonce", res.nonce());
        out.put("required", new String[]{"selfie", "id_front"});
        out.put("optional", new String[]{"id_back"});
        out.put("constraints", Map.of(
                "allowed_content_types", new String[]{"image/jpeg", "image/png"},
                "max_bytes", res.maxBytes()
        ));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/presign")
    public ResponseEntity<?> presign(@AuthenticationPrincipal Jwt jwt,
                                     @PathVariable("communityId") long communityId,
                                     @Validated @RequestBody PresignRequest body) {
        var res = service.presign(jwt.getSubject(), communityId, body.uploadSessionId(), body.kind(), body.contentType(), body.sizeBytes());
        if (res.status() == PhotoIdVerificationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == PhotoIdVerificationService.Status.COMMUNITY_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        if (res.status() == PhotoIdVerificationService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", res.error()));
        }
        if (res.status() == PhotoIdVerificationService.Status.BAD_REQUEST) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", res.error()));
        }
        return ResponseEntity.ok(Map.of(
                "kind", res.kind(),
                "key", res.key(),
                "uploadUrl", res.uploadUrl(),
                "headers", res.headers()
        ));
    }

    @PostMapping("/submit")
    public ResponseEntity<?> submit(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable("communityId") long communityId,
                                    @Validated @RequestBody SubmitRequest body) {
        String email = jwt.getClaimAsString("email");
        var docs = body.documents();
        var res = service.submit(
                jwt.getSubject(),
                communityId,
                email,
                body.uploadSessionId(),
                docs.selfieKey(),
                docs.idFrontKey(),
                docs.idBackKey()
        );
        if (res.status() == PhotoIdVerificationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == PhotoIdVerificationService.Status.COMMUNITY_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        if (res.status() == PhotoIdVerificationService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", res.error()));
        }
        if (res.status() == PhotoIdVerificationService.Status.CONFLICT) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", res.error()));
        }
        if (res.status() == PhotoIdVerificationService.Status.BAD_REQUEST) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", res.error()));
        }
        return ResponseEntity.ok(Map.of(
                "verification_request_id", res.verificationRequestId(),
                "status", "pending_review"
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(@AuthenticationPrincipal Jwt jwt, @PathVariable("communityId") long communityId) {
        var res = service.status(jwt.getSubject(), communityId);
        if (res.status() == PhotoIdVerificationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == PhotoIdVerificationService.Status.COMMUNITY_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        if (res.status() == PhotoIdVerificationService.Status.BAD_REQUEST) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", res.error()));
        }
        Map<String, Object> out = new HashMap<>();
        out.put("method", res.method());
        out.put("status", res.state());
        if (res.verifiedAt() != null) out.put("verified_at", res.verifiedAt());
        if (res.expiresAt() != null) out.put("expires_at", res.expiresAt());
        return ResponseEntity.ok(out);
    }

    public record PresignRequest(
            @JsonAlias("upload_session_id") @NotBlank String uploadSessionId,
            @NotBlank String kind,
            @JsonAlias("content_type") @NotBlank String contentType,
            @JsonAlias("size_bytes") @NotNull Long sizeBytes
    ) {}

    public record SubmitRequest(@JsonAlias("upload_session_id") @NotBlank String uploadSessionId, @NotNull Documents documents) {}

    public record Documents(
            @JsonAlias("selfie_key") @NotBlank String selfieKey,
            @JsonAlias("id_front_key") @NotBlank String idFrontKey,
            @JsonAlias("id_back_key") String idBackKey
    ) {}
}
