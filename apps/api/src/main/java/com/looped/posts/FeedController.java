package com.looped.posts;

import com.looped.polls.PollPayloads;
import com.looped.polls.PollsService;
import com.looped.settings.AppConfigService;
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
    private final PollsService pollsService;
    private final AppConfigService appConfig;

    public FeedController(FeedService feedService, PollsService pollsService, AppConfigService appConfig) {
        this.feedService = feedService;
        this.pollsService = pollsService;
        this.appConfig = appConfig;
    }

    @GetMapping
    public ResponseEntity<?> feed(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @RequestParam(value = "mode", required = false, defaultValue = "for_you") String mode,
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
        var res = feedService.feed(jwt.getSubject(), cursor, lim, resolvedCommunityId, mode);
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
        if (res.status() == FeedService.Status.COMMUNITY_BANNED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_banned",
                    "message", "You are banned from this community"
            ));
        }
        Long viewerPrincipalId = pollsService.viewerPrincipalId(jwt.getSubject());
        String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
        List<Long> postIds = res.items().stream().map(p -> p.id).toList();
        var pollsByPostId = pollsService.viewsByPostId(viewerPrincipalId, postIds);
        List<Map<String, Object>> items = res.items().stream().map(row -> {
            Map<String, Object> payload = PostPayloads.from(row, defaultProfileImageUrl);
            var poll = pollsByPostId.get(row.id);
            if (poll != null) payload.put("poll", PollPayloads.from(poll));
            return payload;
        }).toList();
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("items", items);
        if (res.nextCursor() != null) {
            out.put("next_cursor", res.nextCursor());
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/hashtags")
    public ResponseEntity<?> hashtaggedCommunityPosts(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "communityId", required = false) Long communityId,
            @RequestParam(value = "community_id", required = false) Long communityIdAlt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        Long resolvedCommunityId = communityId != null ? communityId : communityIdAlt;
        if (resolvedCommunityId == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "community_id_required",
                    "message", "community_id must be provided"
            ));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = feedService.hashtagged(jwt.getSubject(), cursor, lim, resolvedCommunityId);
        if (res.status() == FeedService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before reading posts"
            ));
        }
        if (res.status() == FeedService.Status.COMMUNITY_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found",
                    "message", "Community not found"
            ));
        }
        if (res.status() == FeedService.Status.COMMUNITY_BANNED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_banned",
                    "message", "You are banned from this community"
            ));
        }
        Long viewerPrincipalId = pollsService.viewerPrincipalId(jwt.getSubject());
        String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
        List<Long> postIds = res.items().stream().map(p -> p.id).toList();
        var pollsByPostId = pollsService.viewsByPostId(viewerPrincipalId, postIds);
        List<Map<String, Object>> items = res.items().stream().map(row -> {
            Map<String, Object> payload = PostPayloads.from(row, defaultProfileImageUrl);
            var poll = pollsByPostId.get(row.id);
            if (poll != null) payload.put("poll", PollPayloads.from(poll));
            return payload;
        }).toList();
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
        if (res.status() == FeedService.Status.COMMUNITY_BANNED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_banned",
                    "message", "You are banned from this community"
            ));
        }
        Long viewerPrincipalId = pollsService.viewerPrincipalId(jwt.getSubject());
        String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
        List<Long> postIds = res.items().stream().map(p -> p.id).toList();
        var pollsByPostId = pollsService.viewsByPostId(viewerPrincipalId, postIds);
        List<Map<String, Object>> items = res.items().stream().map(row -> {
            Map<String, Object> payload = PostPayloads.trending(row, defaultProfileImageUrl);
            var poll = pollsByPostId.get(row.id);
            if (poll != null) payload.put("poll", PollPayloads.from(poll));
            return payload;
        }).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }
}
