package com.looped.posts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public ResponseEntity<?> repost(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable("postId") long postId,
            @RequestBody(required = false) RepostRequest body
    ) {
        boolean asAnon = body != null && Boolean.TRUE.equals(body.asAnon());
        if (asAnon && (body == null || !body.hasAnonProof())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
        if (asAnon && (actor == null || !actor.equalsIgnoreCase("anon"))) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_actor",
                    "message", "X-Actor: anon is required for anonymous reposts"
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
        var res = service.repost(jwt == null ? null : jwt.getSubject(), postId, body == null ? null : body.toAnonProof());
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
            case COMMUNITY_BANNED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_banned",
                    "message", "You are banned from this community"
            ));
            case INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
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
    public ResponseEntity<?> unrepost(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable("postId") long postId,
            @RequestBody(required = false) RepostRequest body
    ) {
        boolean asAnon = body != null && Boolean.TRUE.equals(body.asAnon());
        if (asAnon && (body == null || !body.hasAnonProof())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
        if (asAnon && (actor == null || !actor.equalsIgnoreCase("anon"))) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_actor",
                    "message", "X-Actor: anon is required for anonymous reposts"
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
        var res = service.unrepost(jwt == null ? null : jwt.getSubject(), postId, body == null ? null : body.toAnonProof());
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
            case COMMUNITY_BANNED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_banned",
                    "message", "You are banned from this community"
            ));
            case INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
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

    public record RepostRequest(Boolean asAnon, Long anonProfileId, String anonCert, String anonCertKid, String anonSig) {
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
}
