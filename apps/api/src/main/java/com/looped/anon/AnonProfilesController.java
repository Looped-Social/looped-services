package com.looped.anon;

import com.looped.posts.PostPayloads;
import com.looped.principals.PrincipalPayloads;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/anon")
public class AnonProfilesController {
    private final AnonProfilesService service;

    public AnonProfilesController(AnonProfilesService service) {
        this.service = service;
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
            case OK -> ResponseEntity.ok(toPostListPayload(res.posts(), res.nextCursor()));
        };
    }

    @GetMapping("/{id}/followers")
    public ResponseEntity<?> followers(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.followers(jwt.getSubject(), id, cursor, lim);
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
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.following(jwt.getSubject(), id, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case OK -> ResponseEntity.ok(toPrincipalListPayload(res.principals(), res.nextCursor()));
        };
    }

    private Map<String, Object> toPostListPayload(List<com.looped.posts.PostRepository.PostRow> posts, String nextCursor) {
        List<Map<String, Object>> items = posts.stream().map(PostPayloads::from).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (nextCursor != null) body.put("next_cursor", nextCursor);
        return body;
    }

    private Map<String, Object> toPrincipalListPayload(List<com.looped.principals.PrincipalProfilesRepository.PrincipalProfileRow> principals, String nextCursor) {
        List<Map<String, Object>> items = principals.stream().map(PrincipalPayloads::directory).toList();
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
}
