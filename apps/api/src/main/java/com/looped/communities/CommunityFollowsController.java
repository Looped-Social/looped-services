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

    public CommunityFollowsController(CommunityFollowsService service) {
        this.service = service;
    }

    @GetMapping("/me/followed/communities")
    public ResponseEntity<?> followedCommunities(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 200));
        var res = service.followed(jwt.getSubject(), cursor, lim);
        if (res.status() == CommunityFollowsService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing followed communities"
            ));
        }
        if (res.status() != CommunityFollowsService.Status.OK) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        List<Map<String, Object>> items = res.follows().stream().map(this::payload).toList();
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
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        return followedCommunities(jwt, cursor, limit);
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
            case INVALID_SPECIALIZATION -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_specialization"
            ));
            case LIMIT_REACHED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "specialization_limit",
                    "message", limitMessage(res.specializationType(), res.limit()),
                    "specialization_type", res.specializationType(),
                    "limit", res.limit()
            ));
            case COOLDOWN -> {
                Map<String, Object> body = new HashMap<>();
                body.put("error", "specialization_cooldown");
                body.put("message", cooldownMessage(res.specializationType()));
                body.put("specialization_type", res.specializationType());
                if (res.cooldownEndsAt() != null) {
                    body.put("cooldown_ends_at", res.cooldownEndsAt());
                    long days = java.time.Duration.between(java.time.OffsetDateTime.now(), res.cooldownEndsAt()).toDays();
                    if (days > 0) body.put("cooldown_days_remaining", days);
                }
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
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

    private Map<String, Object> payload(CommunityFollowsRepository.FollowRow row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.communityId);
        map.put("name", row.name);
        map.put("kind", row.kind);
        if (row.specializationType != null) map.put("specialization_type", row.specializationType);
        map.put("member_count", row.memberCount);
        map.put("is_pinned", row.isPinned);
        map.put("can_post", row.canPost);
        if (row.sortOrder != null) {
            map.put("sort_order", row.sortOrder);
        }
        return map;
    }

    private String limitMessage(String specializationType, Integer limit) {
        if (specializationType == null || limit == null) {
            return "Specialization limit reached";
        }
        String label = specializationType.equals("major") ? "majors" : "departments";
        return "You can only join up to " + limit + " " + label + ".";
    }

    private String cooldownMessage(String specializationType) {
        if (specializationType == null) {
            return "You must wait before changing specializations.";
        }
        String label = specializationType.equals("major") ? "majors" : "departments";
        return "You must wait 6 months before changing " + label + ".";
    }
}
