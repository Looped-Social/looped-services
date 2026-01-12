package com.looped.communities;

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
@RequestMapping("/v1")
public class CommunityFollowsController {
    private final CommunityFollowsService service;
    private final CommunityVerificationsRepository verifications;

    public CommunityFollowsController(CommunityFollowsService service,
                                      CommunityVerificationsRepository verifications) {
        this.service = service;
        this.verifications = verifications;
    }

    @GetMapping("/me/followed/communities")
    public ResponseEntity<?> followedCommunities(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(value = "order", required = false, defaultValue = "relevant") String order
    ) {
        int lim = Math.max(1, Math.min(limit, 200));
        var res = service.followed(jwt.getSubject(), cursor, lim, order);
        if (res.status() == CommunityFollowsService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing followed communities"
            ));
        }
        if (res.status() != CommunityFollowsService.Status.OK) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        var memberCounts = verifications.countActiveVerifiedMembersByCommunityIds(
                res.follows().stream().map(r -> r.communityId).toList()
        );
        List<Map<String, Object>> items = res.follows().stream()
                .map(row -> payload(row, memberCounts))
                .toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (res.nextCursor() != null) {
            body.put("next_cursor", res.nextCursor());
        }
        return ResponseEntity.ok(body);
    }

    // Backwards-compatible alias for older clients.
    @GetMapping("/me/followed/loops")
    public ResponseEntity<?> followedLoops(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(value = "order", required = false, defaultValue = "relevant") String order
    ) {
        return followedCommunities(jwt, cursor, limit, order);
    }

    @PostMapping("/communities/{id}/follow")
    public ResponseEntity<?> followCommunity(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id
    ) {
        var res = service.follow(jwt.getSubject(), id);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case OK -> new ResponseEntity<>(Map.of(
                    "community_id", id,
                    "following", true
            ), res.changed() ? HttpStatus.CREATED : HttpStatus.OK);
        };
    }

    @DeleteMapping("/communities/{id}/follow")
    public ResponseEntity<?> unfollowCommunity(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id
    ) {
        var res = service.unfollow(jwt.getSubject(), id);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case OK -> ResponseEntity.ok(Map.of(
                    "community_id", id,
                    "following", false
            ));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    private Map<String, Object> payload(CommunityFollowsRepository.FollowRow row, java.util.Map<Long, Integer> memberCounts) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.communityId);
        map.put("name", row.name);
        map.put("kind", row.kind);
        if (row.specializationType != null) map.put("specialization_type", row.specializationType);
        map.put("member_count", memberCounts.getOrDefault(row.communityId, 0));
        map.put("is_pinned", row.isPinned);
        map.put("can_post", row.canPost);
        if ("specialization".equalsIgnoreCase(row.kind)) {
            map.put("is_joined", row.isJoined);
        }
        if (row.sortOrder != null) {
            map.put("sort_order", row.sortOrder);
        }
        return map;
    }

    // Specialization join limits/cooldowns are handled by /v1/specializations/{id}/join.
}
