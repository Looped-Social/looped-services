package com.looped.posts;

import jakarta.validation.constraints.NotBlank;
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
            case OK -> new ResponseEntity<>(Map.of("id", res.id(), "content", body.content(), "media_asset_id", body.mediaAssetId()),
                    res.created() ? HttpStatus.CREATED : HttpStatus.OK);
        };
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable("id") long id) {
        var p = postsService.get(id);
        if (p.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        var row = p.get();
        return ResponseEntity.ok(Map.of(
                "id", row.id,
                "author_id", row.authorId,
                "company_id", row.companyId,
                "content", row.content,
                "media_asset_id", row.mediaAssetId,
                "likes_count", row.likesCount,
                "created_at", row.createdAt
        ));
    }

    public record CreateRequest(@NotBlank String content, Long mediaAssetId) {}
}
