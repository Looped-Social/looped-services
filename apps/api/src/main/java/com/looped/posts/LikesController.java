package com.looped.posts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/posts/{postId}/like")
public class LikesController {
    private final LikesService service;

    public LikesController(LikesService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> like(@AuthenticationPrincipal Jwt jwt, @PathVariable("postId") long postId) {
        var res = service.like(jwt.getSubject(), postId);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before reacting"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "Post not found"
            ));
            case OK -> new ResponseEntity<>(Map.of(
                    "post_id", postId,
                    "likes_count", res.likesCount()
            ), res.created() ? HttpStatus.CREATED : HttpStatus.OK);
        };
    }
}
