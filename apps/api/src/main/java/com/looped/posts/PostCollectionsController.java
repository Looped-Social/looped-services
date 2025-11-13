package com.looped.posts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/posts")
public class PostCollectionsController {
    private final PostCollectionsService service;

    public PostCollectionsController(PostCollectionsService service) {
        this.service = service;
    }

    @GetMapping("/liked")
    public ResponseEntity<?> liked(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.liked(jwt.getSubject(), cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing liked posts"
            ));
            case OK -> ResponseEntity.ok(toListPayload(res.posts(), res.nextCursor()));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "unexpected_status",
                    "message", "Unexpected status for liked posts"
            ));
        };
    }

    @GetMapping("/saved")
    public ResponseEntity<?> saved(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.saved(jwt.getSubject(), cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing saved posts"
            ));
            case OK -> ResponseEntity.ok(toListPayload(res.posts(), res.nextCursor()));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "unexpected_status",
                    "message", "Unexpected status for saved posts"
            ));
        };
    }

    @PostMapping("/{postId}/save")
    public ResponseEntity<?> save(@AuthenticationPrincipal Jwt jwt, @PathVariable("postId") long postId) {
        var res = service.save(jwt.getSubject(), postId);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before saving posts"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "Post not found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Cross-company access denied"
            ));
            case OK -> new ResponseEntity<>(Map.of(
                    "post_id", postId,
                    "saved", true
            ), res.changed() ? HttpStatus.CREATED : HttpStatus.OK);
        };
    }

    @DeleteMapping("/{postId}/save")
    public ResponseEntity<?> unsave(@AuthenticationPrincipal Jwt jwt, @PathVariable("postId") long postId) {
        var res = service.unsave(jwt.getSubject(), postId);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before removing saved posts"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "Post not found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Cross-company access denied"
            ));
            case OK -> ResponseEntity.ok(Map.of(
                    "post_id", postId,
                    "saved", false
            ));
        };
    }

    private Map<String, Object> toListPayload(List<PostRepository.PostRow> posts, String nextCursor) {
        List<Map<String, Object>> items = posts.stream().map(PostPayloads::from).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (nextCursor != null) {
            body.put("next_cursor", nextCursor);
        }
        return body;
    }
}

