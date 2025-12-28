package com.looped.posts;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/posts")
public class PostsController {
    private final PostsService postsService;

    public PostsController(PostsService postsService) {
        this.postsService = postsService;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Validated @RequestBody CreateRequest body
    ) {
        Long communityId = body.communityId() != null ? body.communityId() : body.loopId();
        boolean isAnon = body.isAnon() != null && body.isAnon();
        if (isAnon && jwt != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        if (!isAnon && jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        if (!isAnon && (idempotencyKey == null || idempotencyKey.isBlank())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "idempotency_required",
                    "message", "Idempotency-Key is required"
            ));
        }
        var res = postsService.create(
                jwt == null ? null : jwt.getSubject(),
                idempotencyKey,
                body.content(),
                body.mediaAssetId(),
                communityId,
                isAnon,
                body.anonProfileId(),
                body.anonCert(),
                body.anonCertKid(),
                body.anonSig(),
                body.anonTimestamp()
        );
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before creating posts"
            ));
            case COMMUNITY_REQUIRED -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "community_required",
                    "message", "communityId is required"
            ));
            case COMMUNITY_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found",
                    "message", "Community not found"
            ));
            case NOT_VERIFIED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_not_verified",
                    "message", "You must be verified to post to this community"
            ));
            case IDEMPOTENCY_IN_FLIGHT -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "idempotency_in_flight",
                    "message", "A request with this Idempotency-Key is in flight"
            ));
            case IDEMPOTENCY_REQUIRED -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "idempotency_required",
                    "message", "Idempotency-Key is required"
            ));
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case ANON_MEDIA_NOT_ALLOWED -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "anon_media_invalid",
                    "message", "Anonymous media assets must not be user-owned"
            ));
            case OK -> {
                java.util.Map<String, Object> out = new java.util.HashMap<>();
                out.put("id", res.id());
                out.put("content", body.content());
                // media_asset_id may be null; HashMap permits nulls, Map.of does not
                out.put("media_asset_id", body.mediaAssetId());
                out.put("community_id", communityId);
                yield new ResponseEntity<>(out, res.created() ? HttpStatus.CREATED : HttpStatus.OK);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "unexpected_status",
                    "message", "Unexpected status for create"
            ));
        };
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        var res = postsService.getScoped(jwt.getSubject(), id);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case OK -> ResponseEntity.ok(PostPayloads.from(res.post()));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "unexpected_status",
                    "message", "Unexpected status for get"
            ));
        };
    }

    public record CreateRequest(@NotBlank @Size(max = 1000) String content,
                               Long mediaAssetId,
                               Long communityId,
                               Long loopId,
                               Boolean isAnon,
                               Long anonProfileId,
                               String anonCert,
                               String anonCertKid,
                               String anonSig,
                               Long anonTimestamp) {}
}
