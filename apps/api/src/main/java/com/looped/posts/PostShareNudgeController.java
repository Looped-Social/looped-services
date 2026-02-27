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
@RequestMapping("/v1/posts/{postId}/share-nudge")
public class PostShareNudgeController {
    private final PostShareNudgeService service;

    public PostShareNudgeController(PostShareNudgeService service) {
        this.service = service;
    }

    @PostMapping("/serve")
    public ResponseEntity<?> serve(@AuthenticationPrincipal Jwt jwt, @PathVariable("postId") long postId) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        var res = service.serve(jwt.getSubject(), postId);
        return toResponse(postId, res, true);
    }

    @PostMapping("/dismiss")
    public ResponseEntity<?> dismiss(@AuthenticationPrincipal Jwt jwt, @PathVariable("postId") long postId) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        var res = service.dismiss(jwt.getSubject(), postId);
        return toResponse(postId, res, false);
    }

    @PostMapping("/share-tap")
    public ResponseEntity<?> shareTap(@AuthenticationPrincipal Jwt jwt, @PathVariable("postId") long postId) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        var res = service.shareTap(jwt.getSubject(), postId);
        return toResponse(postId, res, false);
    }

    private ResponseEntity<?> toResponse(long postId, PostShareNudgeService.MutationResult res, boolean includeServeFields) {
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before mutating share nudges"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "Post not found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Only the post author may mutate share nudge state"
            ));
            case OK -> {
                var body = new java.util.HashMap<String, Object>();
                body.put("post_id", postId);
                body.put("served", res.served());
                if (includeServeFields) {
                    body.put("share_nudge", res.served() ? res.sharePayload() : null);
                    body.put("shareNudge", res.served() ? res.sharePayload() : null);
                }
                if (res.state() != null) {
                    body.put("eligible_at", res.state().eligibleAt());
                    body.put("first_served_at", res.state().firstServedAt());
                    body.put("dismissed_at", res.state().dismissedAt());
                    body.put("share_tapped_at", res.state().shareTappedAt());
                    body.put("variant", res.state().variant());
                }
                if (res.sharePayload() != null && !includeServeFields) {
                    body.put("share_payload", res.sharePayload());
                    body.put("sharePayload", res.sharePayload());
                }
                yield ResponseEntity.ok(body);
            }
        };
    }
}
