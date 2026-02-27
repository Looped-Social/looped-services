package com.looped.posts;

import com.looped.polls.PollPayloads;
import com.looped.polls.PollsService;
import com.looped.settings.AppConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class UserSavedPostsController {
    private final PostCollectionsService service;
    private final PollsService pollsService;
    private final AppConfigService appConfig;
    private final PostViewerCapabilitiesService viewerCapabilities;
    private final PostViewCountsService postViewCounts;
    private final PostShareNudgeService shareNudges;

    public UserSavedPostsController(PostCollectionsService service,
                                    PollsService pollsService,
                                    AppConfigService appConfig,
                                    PostViewerCapabilitiesService viewerCapabilities,
                                    PostViewCountsService postViewCounts,
                                    PostShareNudgeService shareNudges) {
        this.service = service;
        this.pollsService = pollsService;
        this.appConfig = appConfig;
        this.viewerCapabilities = viewerCapabilities;
        this.postViewCounts = postViewCounts;
        this.shareNudges = shareNudges;
    }

    @GetMapping("/v1/users/{id}/posts/saved")
    public ResponseEntity<?> saved(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.savedForUser(jwt.getSubject(), id, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing saved posts"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found"
            ));
            case OK -> {
                Long viewerPrincipalId = pollsService.viewerPrincipalId(jwt.getSubject());
                String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
                List<Long> postIds = res.posts().stream().map(p -> p.id).toList();
                var pollsByPostId = pollsService.viewsByPostId(viewerPrincipalId, postIds);
                var capabilitiesByPostId = viewerCapabilities.byPostId(jwt.getSubject(), res.posts(), pollsByPostId);
                var viewCountsByPostId = postViewCounts.authorVisibleUniqueViewCounts(jwt.getSubject(), res.posts());
                var shareNudgesByPostId = shareNudges.evaluateAndMaybeServe(jwt.getSubject(), res.posts());
                List<Map<String, Object>> items = res.posts().stream().map(row -> {
                    Map<String, Object> payload = PostPayloads.from(row, defaultProfileImageUrl);
                    var poll = pollsByPostId.get(row.id);
                    if (poll != null) payload.put("poll", PollPayloads.from(poll));
                    PostPayloads.putViewerCapabilities(payload, capabilitiesByPostId.get(row.id));
                    PostPayloads.putViewCount(payload, viewCountsByPostId.get(row.id));
                    PostPayloads.putShareNudge(payload, shareNudgesByPostId.get(row.id));
                    return payload;
                }).toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }
}
