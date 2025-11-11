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
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Validated @RequestBody CreateRequest body
    ) {
        var res = postsService.create(jwt.getSubject(), idempotencyKey, body.content(), body.mediaAssetId());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before creating posts"
            ));
            case IDEMPOTENCY_IN_FLIGHT -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "idempotency_in_flight",
                    "message", "A request with this Idempotency-Key is in flight"
            ));
            case OK -> {
                java.util.Map<String, Object> out = new java.util.HashMap<>();
                out.put("id", res.id());
                out.put("content", body.content());
                // media_asset_id may be null; HashMap permits nulls, Map.of does not
                out.put("media_asset_id", body.mediaAssetId());
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
            case OK -> {
                var row = res.post();
                java.util.Map<String, Object> out = new java.util.HashMap<>();
                out.put("id", row.id);
                out.put("author_id", row.authorId);
                out.put("company_id", row.companyId);
                out.put("content", row.content);
                // Allow null optional fields
                out.put("media_asset_id", row.mediaAssetId);
                out.put("likes_count", row.likesCount);
                out.put("created_at", row.createdAt);
                yield ResponseEntity.ok(out);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "unexpected_status",
                    "message", "Unexpected status for get"
            ));
        };
    }

    public record CreateRequest(@NotBlank @Size(max = 1000) String content, Long mediaAssetId) {}
}
