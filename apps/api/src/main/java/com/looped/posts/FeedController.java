package com.looped.posts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/feed")
public class FeedController {
    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping
    public ResponseEntity<?> feed(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @RequestParam(value = "communityId", required = false) Long communityId,
            @RequestParam(value = "community_id", required = false) Long communityIdAlt,
            @RequestParam(value = "loopId", required = false) Long loopIdLegacy,
            @RequestParam(value = "loop_id", required = false) Long loopIdLegacyAlt
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        Long resolvedCommunityId = communityId != null ? communityId : communityIdAlt;
        if (resolvedCommunityId == null) {
            resolvedCommunityId = loopIdLegacy != null ? loopIdLegacy : loopIdLegacyAlt;
        }
        var res = feedService.feed(jwt.getSubject(), cursor, lim, resolvedCommunityId);
        if (res.status() == FeedService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before reading feed"
            ));
        }
        if (res.status() == FeedService.Status.COMMUNITY_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found",
                    "message", "Community not found"
            ));
        }
        List<Map<String, Object>> items = res.items().stream()
                .map(PostPayloads::from)
                .toList();
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("items", items);
        if (res.nextCursor() != null) {
            out.put("next_cursor", res.nextCursor());
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/trending")
    public ResponseEntity<?> trending(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "limit", required = false, defaultValue = "3") int limit,
            @RequestParam(value = "communityId", required = false) Long communityId,
            @RequestParam(value = "community_id", required = false) Long communityIdAlt
    ) {
        int lim = Math.max(1, Math.min(limit, 10));
        Long resolvedCommunityId = communityId != null ? communityId : communityIdAlt;
        var res = feedService.trending(jwt.getSubject(), lim, resolvedCommunityId);
        if (res.status() == FeedService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing trending posts"
            ));
        }
        if (res.status() == FeedService.Status.COMMUNITY_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found",
                    "message", "Community not found"
            ));
        }
        List<Map<String, Object>> items = res.items().stream()
                .map(PostPayloads::trending)
                .toList();
        return ResponseEntity.ok(Map.of("items", items));
    }
}
