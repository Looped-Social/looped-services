package com.looped.posts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
            case OK -> ResponseEntity.ok(toListPayload(res.posts(), res.nextCursor(), true));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "unexpected_status",
                    "message", "Unexpected status for saved posts"
            ));
        };
    }

    @PostMapping("/{postId}/save")
    public ResponseEntity<?> save(@AuthenticationPrincipal Jwt jwt, @PathVariable("postId") long postId,
                                  @RequestBody(required = false) SaveRequest body) {
        boolean asAnon = body != null && Boolean.TRUE.equals(body.asAnon());
        if (asAnon && (body == null || !body.hasAnonProof())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
        if (asAnon && jwt != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        if (!asAnon && jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        var res = service.save(jwt == null ? null : jwt.getSubject(), postId, body == null ? null : body.toAnonProof());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before saving posts"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "Post not found"
            ));
            case INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> new ResponseEntity<>(Map.of(
                    "post_id", postId,
                    "saved", true
            ), res.changed() ? HttpStatus.CREATED : HttpStatus.OK);
        };
    }

    @DeleteMapping("/{postId}/save")
    public ResponseEntity<?> unsave(@AuthenticationPrincipal Jwt jwt, @PathVariable("postId") long postId,
                                    @RequestBody(required = false) SaveRequest body) {
        boolean asAnon = body != null && Boolean.TRUE.equals(body.asAnon());
        if (asAnon && (body == null || !body.hasAnonProof())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
        if (asAnon && jwt != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        if (!asAnon && jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        var res = service.unsave(jwt == null ? null : jwt.getSubject(), postId, body == null ? null : body.toAnonProof());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before removing saved posts"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "Post not found"
            ));
            case INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> ResponseEntity.ok(Map.of(
                    "post_id", postId,
                    "saved", false
            ));
        };
    }

    public record SaveRequest(Boolean asAnon, Long anonProfileId, String anonCert, String anonCertKid, String anonSig) {
        com.looped.anon.AnonProofService.AnonActionProof toAnonProof() {
            if (asAnon == null || !asAnon) return null;
            return new com.looped.anon.AnonProofService.AnonActionProof(anonProfileId, anonCert, anonCertKid, anonSig);
        }

        boolean hasAnonProof() {
            if (asAnon == null || !asAnon) return false;
            return anonProfileId != null
                    && anonCert != null && !anonCert.isBlank()
                    && anonCertKid != null && !anonCertKid.isBlank()
                    && anonSig != null && !anonSig.isBlank();
        }
    }

    private Map<String, Object> toListPayload(List<PostRepository.PostRow> posts, String nextCursor, boolean isSaved) {
        List<Map<String, Object>> items = posts.stream()
                .map(p -> isSaved ? PostPayloads.fromSaved(p, true) : PostPayloads.from(p))
                .toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (nextCursor != null) {
            body.put("next_cursor", nextCursor);
        }
        return body;
    }

    private Map<String, Object> toListPayload(List<PostRepository.PostRow> posts, String nextCursor) {
        return toListPayload(posts, nextCursor, false);
    }
}
