package com.looped.anon;

import com.looped.posts.PostPayloads;
import com.looped.posts.PostViewerCapabilitiesService;
import com.looped.principals.PrincipalPayloads;
import com.looped.comments.CommentPayloads;
import com.looped.settings.AppConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/anon")
public class AnonProfilesController {
    private final AnonProfilesService service;
    private final AppConfigService appConfig;
    private final PostViewerCapabilitiesService viewerCapabilities;

    public AnonProfilesController(AnonProfilesService service,
                                  AppConfigService appConfig,
                                  PostViewerCapabilitiesService viewerCapabilities) {
        this.service = service;
        this.appConfig = appConfig;
        this.viewerCapabilities = viewerCapabilities;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> profile(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        var res = service.profile(jwt.getSubject(), id);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case OK -> ResponseEntity.ok(toAnonProfilePayload(res.profile()));
        };
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}/display-community")
    public ResponseEntity<?> updateDisplayCommunity(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody DisplayCommunityRequest body
    ) {
        if (jwt != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        if (body == null || !body.hasAnonProof()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
        var res = service.updateDisplayCommunity(id, body.communityId(), body.toAnonProof());
        return switch (res.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case COMMUNITY_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> ResponseEntity.ok(toAnonProfilePayload(res.profile()));
        };
    }

    @PutMapping("/{id}/display-specialization")
    public ResponseEntity<?> updateDisplaySpecialization(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable("id") long id,
            @RequestBody DisplaySpecializationRequest body
    ) {
        if (jwt != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        if (actor == null || !actor.equalsIgnoreCase("anon")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_actor",
                    "message", "X-Actor: anon is required for this endpoint"
            ));
        }
        if (body == null || !body.hasAnonProof()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
        var res = service.updateDisplaySpecialization(id, body.specializationId(), body.toAnonProof());
        return switch (res.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case SPECIALIZATION_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "specialization_not_found"));
            case INVALID_SPECIALIZATION -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_specialization"));
            case SPECIALIZATION_NOT_JOINED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "specialization_not_joined"));
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> ResponseEntity.ok(toAnonProfilePayload(res.profile()));
        };
    }

    @GetMapping("/{id}/posts")
    public ResponseEntity<?> posts(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.posts(jwt.getSubject(), id, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case OK -> ResponseEntity.ok(toPostListPayload(jwt.getSubject(), res.posts(), res.nextCursor()));
        };
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<?> content(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @RequestParam(value = "include_post_preview", required = false, defaultValue = "false") boolean includePostPreview
    ) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.content(jwt.getSubject(), id, cursor, lim, includePostPreview);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case OK -> {
                Map<String, Object> body = new HashMap<>();
                body.put("items", res.items());
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
        };
    }

    @GetMapping("/{id}/reposts")
    public ResponseEntity<?> reposts(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.reposts(jwt.getSubject(), id, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case OK -> ResponseEntity.ok(toPostListPayload(jwt.getSubject(), res.posts(), res.nextCursor()));
        };
    }

    @GetMapping("/{id}/posts/liked")
    public ResponseEntity<?> likedPosts(
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
        if (!isAnon) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
        if (jwt != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.likedPosts(id, cursor, lim, toAnonProof(asAnon, anonProfileId, anonCert, anonCertKid, anonSig));
        return switch (res.status()) {
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> ResponseEntity.ok(toPostListPayload(null, res.posts(), res.nextCursor()));
        };
    }

    @GetMapping("/{id}/posts/saved")
    public ResponseEntity<?> savedPosts(
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
        if (!isAnon) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
        if (jwt != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.savedPosts(id, cursor, lim, toAnonProof(asAnon, anonProfileId, anonCert, anonCertKid, anonSig));
        return switch (res.status()) {
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> ResponseEntity.ok(toPostListPayload(null, res.posts(), res.nextCursor()));
        };
    }

    @GetMapping("/{id}/replies")
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
        int lim = Math.max(1, Math.min(limit, 100));
        if (isAnon) {
            if (!hasAnonProof(asAnon, anonProfileId, anonCert, anonCertKid, anonSig)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", "invalid_anon_proof",
                        "message", "Invalid anonymous proof"
                ));
            }
            if (jwt != null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                        "error", "anon_jwt_not_allowed",
                        "message", "Do not send Authorization for anonymous actions"
                ));
            }
            var res = service.replies(id, cursor, lim, toAnonProof(asAnon, anonProfileId, anonCert, anonCertKid, anonSig));
            return switch (res.status()) {
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
            };
        }
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        var res = service.repliesForViewer(jwt.getSubject(), id, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden"
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
        };
    }

    @PostMapping("/{id}/follow")
    public ResponseEntity<?> follow(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable("id") long id,
            @RequestBody(required = false) FollowRequest body
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
                    "message", "X-Actor: anon is required for anonymous follows"
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
        var res = service.followAnonProfile(jwt == null ? null : jwt.getSubject(), id, body == null ? null : body.toAnonProof());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case ACTOR_NOT_SCOPED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "actor_not_scoped",
                    "message", "Anonymous actor certificate is not scoped to the target company"
            ));
            case CROSS_COMPANY_FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "cross_company_follow_forbidden",
                    "message", "Actor and target anonymous profile must belong to the same company"
            ));
            case INVALID_TARGET -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_target",
                    "message", "Cannot follow this anonymous profile"
            ));
            case INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> new ResponseEntity<>(Map.of(
                    "anon_profile_id", id,
                    "id", id,
                    "following", true
            ), res.changed() ? HttpStatus.CREATED : HttpStatus.OK);
        };
    }

    @DeleteMapping("/{id}/follow")
    public ResponseEntity<?> unfollow(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @PathVariable("id") long id,
            @RequestBody(required = false) FollowRequest body
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
                    "message", "X-Actor: anon is required for anonymous follows"
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
        var res = service.unfollowAnonProfile(jwt == null ? null : jwt.getSubject(), id, body == null ? null : body.toAnonProof());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case ACTOR_NOT_SCOPED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "actor_not_scoped",
                    "message", "Anonymous actor certificate is not scoped to the target company"
            ));
            case CROSS_COMPANY_FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "cross_company_follow_forbidden",
                    "message", "Actor and target anonymous profile must belong to the same company"
            ));
            case INVALID_TARGET -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_target",
                    "message", "Cannot unfollow this anonymous profile"
            ));
            case INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> ResponseEntity.ok(Map.of(
                    "anon_profile_id", id,
                    "id", id,
                    "following", false
            ));
        };
    }

    @GetMapping("/{id}/followers")
    public ResponseEntity<?> followers(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.followers(jwt.getSubject(), id, query, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case OK -> ResponseEntity.ok(toPrincipalListPayload(res.principals(), res.nextCursor()));
        };
    }

    @GetMapping("/{id}/following")
    public ResponseEntity<?> following(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.following(jwt.getSubject(), id, query, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case OK -> ResponseEntity.ok(toPrincipalListPayload(res.principals(), res.nextCursor()));
        };
    }

    private Map<String, Object> toPostListPayload(String firebaseUid,
                                                  List<com.looped.posts.PostRepository.PostRow> posts,
                                                  String nextCursor) {
        String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
        var capabilitiesByPostId = viewerCapabilities.byPostId(firebaseUid, posts, Map.of());
        List<Map<String, Object>> items = posts.stream().map(row -> {
            Map<String, Object> payload = PostPayloads.from(row, defaultProfileImageUrl);
            PostPayloads.putViewerCapabilities(payload, capabilitiesByPostId.get(row.id));
            return payload;
        }).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (nextCursor != null) body.put("next_cursor", nextCursor);
        return body;
    }

    private Map<String, Object> toPrincipalListPayload(List<com.looped.principals.PrincipalProfilesRepository.PrincipalProfileRow> principals, String nextCursor) {
        String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
        List<Map<String, Object>> items = principals.stream().map(row -> PrincipalPayloads.directory(row, defaultProfileImageUrl)).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (nextCursor != null) body.put("next_cursor", nextCursor);
        return body;
    }

    private Map<String, Object> toAnonProfilePayload(AnonProfilesService.AnonProfile profile) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", profile.id());
        body.put("handle", profile.handle());
        body.put("company_id", profile.companyId());
        body.put("created_at", profile.createdAt());
        if (profile.displayCommunity() != null) {
            Map<String, Object> display = new HashMap<>();
            display.put("id", profile.displayCommunity().id());
            display.put("name", profile.displayCommunity().name());
            display.put("kind", profile.displayCommunity().kind());
            if (profile.displayCommunity().specializationType() != null) {
                display.put("specialization_type", profile.displayCommunity().specializationType());
            }
            body.put("display_community", display);
        } else {
            body.put("display_community", null);
        }
        if (profile.displaySpecialization() != null) {
            Map<String, Object> display = new HashMap<>();
            display.put("id", profile.displaySpecialization().id());
            display.put("name", profile.displaySpecialization().name());
            display.put("kind", profile.displaySpecialization().kind());
            if (profile.displaySpecialization().specializationType() != null) {
                display.put("specialization_type", profile.displaySpecialization().specializationType());
            }
            body.put("display_specialization", display);
        } else {
            body.put("display_specialization", null);
        }
        Map<String, Object> stats = new HashMap<>();
        stats.put("follower_count", profile.stats().followerCount());
        stats.put("following_count", profile.stats().followingCount());
        stats.put("posts_count", profile.stats().postsCount());
        body.put("stats", stats);
        return body;
    }

    public record DisplayCommunityRequest(Long communityId, Boolean asAnon, Long anonProfileId,
                                          String anonCert, String anonCertKid, String anonSig) {
        AnonProofService.AnonActionProof toAnonProof() {
            return new AnonProofService.AnonActionProof(anonProfileId, anonCert, anonCertKid, anonSig);
        }

        boolean hasAnonProof() {
            return anonProfileId != null
                    && anonCert != null && !anonCert.isBlank()
                    && anonCertKid != null && !anonCertKid.isBlank()
                    && anonSig != null && !anonSig.isBlank()
                    && Boolean.TRUE.equals(asAnon);
        }
    }

    public record FollowRequest(
            @JsonAlias("as_anon") Boolean asAnon,
            @JsonAlias("anon_profile_id") Long anonProfileId,
            @JsonAlias("anon_cert") String anonCert,
            @JsonAlias("anon_cert_kid") String anonCertKid,
            @JsonAlias("anon_sig") String anonSig
    ) {
        AnonProofService.AnonActionProof toAnonProof() {
            if (asAnon == null || !asAnon) return null;
            return new AnonProofService.AnonActionProof(anonProfileId, anonCert, anonCertKid, anonSig);
        }

        boolean hasAnonProof() {
            return anonProfileId != null
                    && anonCert != null && !anonCert.isBlank()
                    && anonCertKid != null && !anonCertKid.isBlank()
                    && anonSig != null && !anonSig.isBlank()
                    && Boolean.TRUE.equals(asAnon);
        }
    }

    public record DisplaySpecializationRequest(
            @JsonAlias("specialization_id") Long specializationId,
            @JsonAlias("as_anon") Boolean asAnon,
            @JsonAlias("anon_profile_id") Long anonProfileId,
            @JsonAlias("anon_cert") String anonCert,
            @JsonAlias("anon_cert_kid") String anonCertKid,
            @JsonAlias("anon_sig") String anonSig
    ) {
        AnonProofService.AnonActionProof toAnonProof() {
            return new AnonProofService.AnonActionProof(anonProfileId, anonCert, anonCertKid, anonSig);
        }

        boolean hasAnonProof() {
            return anonProfileId != null
                    && anonCert != null && !anonCert.isBlank()
                    && anonCertKid != null && !anonCertKid.isBlank()
                    && anonSig != null && !anonSig.isBlank()
                    && Boolean.TRUE.equals(asAnon);
        }
    }

    private com.looped.anon.AnonProofService.AnonActionProof toAnonProof(Boolean asAnon, Long anonProfileId,
                                                                         String anonCert, String anonCertKid, String anonSig) {
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
}
