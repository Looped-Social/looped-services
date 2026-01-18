package com.looped.comments;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.looped.settings.AppConfigService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class CommentsController {
    private final CommentsService service;
    private final AppConfigService appConfig;

    public CommentsController(CommentsService service, AppConfigService appConfig) {
        this.service = service;
        this.appConfig = appConfig;
    }

    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<?> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("postId") long postId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @RequestParam(value = "asAnon", required = false) Boolean asAnon,
            @RequestParam(value = "anonProfileId", required = false) Long anonProfileId,
            @RequestParam(value = "anonCert", required = false) String anonCert,
            @RequestParam(value = "anonCertKid", required = false) String anonCertKid,
            @RequestParam(value = "anonSig", required = false) String anonSig
    ) {
        boolean isAnon = Boolean.TRUE.equals(asAnon);
        if (isAnon && !hasAnonProof(asAnon, anonProfileId, anonCert, anonCertKid, anonSig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
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
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.list(jwt == null ? null : jwt.getSubject(), postId, cursor, lim,
                toAnonProof(asAnon, anonProfileId, anonCert, anonCertKid, anonSig));
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before reading comments"
            ));
            case POST_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found"
            ));
            case COMMUNITY_BANNED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_banned",
                    "message", "You are banned from this community"
            ));
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> {
                String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
                List<Map<String, Object>> items = res.comments().stream()
                        .map(row -> CommentPayloads.from(row, defaultProfileImageUrl))
                        .toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<?> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("postId") long postId,
            @Validated @RequestBody CreateRequest body
    ) {
        boolean isAnon = body.asAnon() != null && body.asAnon();
        if (isAnon && !body.hasAnonProof()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
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
        var res = service.create(jwt == null ? null : jwt.getSubject(), postId, body.content(), body.mediaAssetId(), body.parentId(), body.toAnonProof());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before commenting"
            ));
            case POST_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "Post not found"
            ));
            case COMMENT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "parent_not_found",
                    "message", "Parent comment not found"
            ));
            case USER_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "user_not_found",
                    "message", "User not found"
            ));
            case COMMUNITY_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found",
                    "message", "Community not found"
            ));
            case COMMUNITY_BANNED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_banned",
                    "message", "You are banned from this community"
            ));
            case NOT_VERIFIED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_not_verified",
                    "message", "You must be verified to comment in this community"
            ));
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case INVALID_PARENT -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_parent",
                    "message", "Parent comment must belong to the same post"
            ));
            case ANON_MEDIA_NOT_ALLOWED -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "anon_media_invalid",
                    "message", "Anonymous media assets must not be user-owned"
            ));
            case OK -> ResponseEntity.status(HttpStatus.CREATED).body(CommentPayloads.from(res.comment(), appConfig.defaultProfileImageUrl()));
        };
    }

    @PutMapping("/comments/{id}")
    public ResponseEntity<?> edit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @Validated @RequestBody EditRequest body
    ) {
        boolean isAnon = body.asAnon() != null && body.asAnon();
        if (isAnon && !body.hasAnonProof()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
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
        var res = service.edit(jwt == null ? null : jwt.getSubject(), id, body.content(), body.toAnonProof());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before editing comments"
            ));
            case COMMENT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Only the comment author may edit"
            ));
            case COMMENT_DELETED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "comment_deleted",
                    "message", "Comment has been deleted"
            ));
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> ResponseEntity.ok(CommentPayloads.from(res.comment(), appConfig.defaultProfileImageUrl()));
        };
    }

    @DeleteMapping("/comments/{id}")
    public ResponseEntity<?> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) DeleteRequest body
    ) {
        boolean isAnon = body != null && Boolean.TRUE.equals(body.asAnon());
        if (isAnon && (body == null || !body.hasAnonProof())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
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
        var res = service.delete(jwt == null ? null : jwt.getSubject(), id, body == null ? null : body.toAnonProof());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before deleting comments"
            ));
            case COMMENT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found"
            ));
            case COMMUNITY_BANNED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_banned",
                    "message", "You are banned from this community"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Only the comment author may delete"
            ));
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> ResponseEntity.ok(Map.of(
                    "id", id,
                    "deleted", res.deleted()
            ));
        };
    }

    @GetMapping("/comments/{id}/replies")
    public ResponseEntity<?> replies(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @RequestParam(value = "asAnon", required = false) Boolean asAnon,
            @RequestParam(value = "anonProfileId", required = false) Long anonProfileId,
            @RequestParam(value = "anonCert", required = false) String anonCert,
            @RequestParam(value = "anonCertKid", required = false) String anonCertKid,
            @RequestParam(value = "anonSig", required = false) String anonSig
    ) {
        boolean isAnon = Boolean.TRUE.equals(asAnon);
        if (isAnon && !hasAnonProof(asAnon, anonProfileId, anonCert, anonCertKid, anonSig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
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
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.replies(jwt == null ? null : jwt.getSubject(), id, cursor, lim,
                toAnonProof(asAnon, anonProfileId, anonCert, anonCertKid, anonSig));
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case COMMENT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found"
            ));
            case COMMUNITY_BANNED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_banned",
                    "message", "You are banned from this community"
            ));
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> {
                String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
                List<Map<String, Object>> items = res.comments().stream()
                        .map(row -> CommentPayloads.from(row, defaultProfileImageUrl))
                        .toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @PostMapping("/comments/{id}/like")
    public ResponseEntity<?> like(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) LikeRequest body
    ) {
        boolean isAnon = body != null && Boolean.TRUE.equals(body.asAnon());
        if (isAnon && (body == null || !body.hasAnonProof())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
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
        var res = service.like(jwt == null ? null : jwt.getSubject(), id, body == null ? null : body.toAnonProof());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before reacting"
            ));
            case COMMENT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found"
            ));
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> new ResponseEntity<>(Map.of(
                    "comment_id", id,
                    "likes_count", res.likesCount(),
                    "user_liked", res.userLiked(),
                    "liked_by_creator", res.likedByCreator()
            ), res.created() ? HttpStatus.CREATED : HttpStatus.OK);
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @DeleteMapping("/comments/{id}/like")
    public ResponseEntity<?> unlike(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) LikeRequest body
    ) {
        boolean isAnon = body != null && Boolean.TRUE.equals(body.asAnon());
        if (isAnon && (body == null || !body.hasAnonProof())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
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
        var res = service.unlike(jwt == null ? null : jwt.getSubject(), id, body == null ? null : body.toAnonProof());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before reacting"
            ));
            case COMMENT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found"
            ));
            case COMMUNITY_BANNED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_banned",
                    "message", "You are banned from this community"
            ));
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> ResponseEntity.ok(Map.of(
                    "comment_id", id,
                    "likes_count", res.likesCount(),
                    "user_liked", res.userLiked(),
                    "liked_by_creator", res.likedByCreator()
            ));
        };
    }

    private com.looped.anon.AnonProofService.AnonActionProof toAnonProof(Boolean asAnon, Long anonProfileId, String anonCert, String anonCertKid, String anonSig) {
        if (asAnon == null || !asAnon) return null;
        return new com.looped.anon.AnonProofService.AnonActionProof(anonProfileId, anonCert, anonCertKid, anonSig);
    }

    private boolean hasAnonProof(Boolean asAnon, Long anonProfileId, String anonCert, String anonCertKid, String anonSig) {
        if (asAnon == null || !asAnon) return false;
        return anonProfileId != null
                && anonCert != null && !anonCert.isBlank()
                && anonCertKid != null && !anonCertKid.isBlank()
                && anonSig != null && !anonSig.isBlank();
    }

    public record CreateRequest(@NotBlank @Size(max = 1000) String content,
                                Long mediaAssetId,
                                Long parentId,
                                Boolean asAnon,
                                Long anonProfileId,
                                String anonCert,
                                String anonCertKid,
                                String anonSig) {
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

    public record EditRequest(@NotBlank @Size(max = 1000) String content,
                              Boolean asAnon,
                              Long anonProfileId,
                              String anonCert,
                              String anonCertKid,
                              String anonSig) {
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

    public record DeleteRequest(Boolean asAnon, Long anonProfileId, String anonCert, String anonCertKid, String anonSig) {
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

    public record LikeRequest(Boolean asAnon, Long anonProfileId, String anonCert, String anonCertKid, String anonSig) {
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
