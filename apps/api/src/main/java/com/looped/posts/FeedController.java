package com.looped.posts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = feedService.feed(jwt.getSubject(), cursor, lim);
        if (res.status() == FeedService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before reading feed"
            ));
        }
        List<Map<String, Object>> items = res.items().stream().map(p -> {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", p.id);
            m.put("author_id", p.authorId);
            m.put("company_id", p.companyId);
            m.put("content", p.content);
            // Allow nulls for optional fields (HashMap permits null values; Map.of does not)
            m.put("media_asset_id", p.mediaAssetId);
            m.put("likes_count", p.likesCount);
            m.put("created_at", p.createdAt);
            return m;
        }).toList();
        return ResponseEntity.ok(Map.of(
                "items", items,
                "next_cursor", res.nextCursor()
        ));
    }
}
