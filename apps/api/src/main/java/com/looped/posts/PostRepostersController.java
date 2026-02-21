package com.looped.posts;

import com.looped.settings.AppConfigService;
import com.looped.users.ProfileImageUrls;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/posts/{postId}/reposters")
public class PostRepostersController {
    private final RepostsService repostsService;
    private final AppConfigService appConfig;

    public PostRepostersController(RepostsService repostsService, AppConfigService appConfig) {
        this.repostsService = repostsService;
        this.appConfig = appConfig;
    }

    @GetMapping
    public ResponseEntity<?> reposters(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("postId") long postId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = repostsService.reposters(jwt.getSubject(), postId, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing reposters"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "Post not found"
            ));
            case COMMUNITY_BANNED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_banned",
                    "message", "You are banned from this community"
            ));
            case OK -> {
                String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
                var items = res.reposters().stream().map(row -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("repost_id", row.repostId());
                    item.put("repostId", row.repostId());
                    item.put("reposted_at", row.repostedAt());
                    item.put("repostedAt", row.repostedAt());
                    item.put("user_id", row.userId());
                    item.put("userId", row.userId());
                    item.put("username", row.username());
                    item.put("display_name", row.displayName());
                    item.put("displayName", row.displayName());
                    item.put("handle", row.handle());
                    String profileImageUrl = ProfileImageUrls.resolve(row.profileImageUrl(), defaultProfileImageUrl);
                    item.put("profile_image_url", profileImageUrl);
                    item.put("profileImageUrl", profileImageUrl);
                    return item;
                }).toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) {
                    body.put("next_cursor", res.nextCursor());
                    body.put("nextCursor", res.nextCursor());
                }
                yield ResponseEntity.ok(body);
            }
        };
    }
}
