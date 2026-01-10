package com.looped.posts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/posts/{postId}/repost")
public class RepostsController {
    private final RepostsService service;

    public RepostsController(RepostsService service) {
        this.service = service;
    }

    @PutMapping
    public ResponseEntity<?> repost(@AuthenticationPrincipal Jwt jwt, @PathVariable("postId") long postId) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        var res = service.repost(jwt.getSubject(), postId);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before reposting"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "Post not found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden"
            ));
            case SELF_REPOST_NOT_ALLOWED -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "self_repost_not_allowed",
                    "message", "You cannot repost your own post"
            ));
            case OK -> new ResponseEntity<>(Map.of(
                    "post_id", postId,
                    "repost_count", res.repostCount(),
                    "repostCount", res.repostCount(),
                    "viewer_has_reposted", true,
                    "viewerHasReposted", true
            ), res.changed() ? HttpStatus.CREATED : HttpStatus.OK);
        };
    }

    @DeleteMapping
    public ResponseEntity<?> unrepost(@AuthenticationPrincipal Jwt jwt, @PathVariable("postId") long postId) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        var res = service.unrepost(jwt.getSubject(), postId);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before reposting"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "Post not found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden"
            ));
            case OK -> ResponseEntity.ok(Map.of(
                    "post_id", postId,
                    "repost_count", res.repostCount(),
                    "repostCount", res.repostCount(),
                    "viewer_has_reposted", false,
                    "viewerHasReposted", false
            ));
            case SELF_REPOST_NOT_ALLOWED -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "self_repost_not_allowed",
                    "message", "You cannot repost your own post"
            ));
        };
    }
}

