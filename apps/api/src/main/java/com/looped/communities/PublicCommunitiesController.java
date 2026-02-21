package com.looped.communities;

import com.looped.polls.PollPayloads;
import com.looped.polls.PollsService;
import com.looped.posts.PostPayloads;
import com.looped.settings.AppConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/public/communities")
public class PublicCommunitiesController {
    private final PublicCommunitiesService service;
    private final PollsService pollsService;
    private final AppConfigService appConfig;

    public PublicCommunitiesController(PublicCommunitiesService service,
                                       PollsService pollsService,
                                       AppConfigService appConfig) {
        this.service = service;
        this.pollsService = pollsService;
        this.appConfig = appConfig;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable("id") long id) {
        var res = service.getById(id);
        return switch (res.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found",
                    "message", "Community not found"
            ));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "error", "community_unavailable",
                    "message", "Community is unavailable"
            ));
            case OK -> ResponseEntity.ok(res.community());
        };
    }

    @GetMapping("/{id}/posts")
    public ResponseEntity<?> posts(
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.postsById(id, cursor, lim);
        return switch (res.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found",
                    "message", "Community not found"
            ));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "error", "community_unavailable",
                    "message", "Community is unavailable"
            ));
            case OK -> ResponseEntity.ok(toPublicPostsPayload(res.posts(), res.nextCursor()));
        };
    }

    private Map<String, Object> toPublicPostsPayload(List<com.looped.posts.PostRepository.PostRow> posts,
                                                     String nextCursor) {
        String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
        List<Long> postIds = posts == null ? List.of() : posts.stream().map(p -> p.id).toList();
        var pollsByPostId = pollsService.viewsByPostId(null, postIds);
        List<Map<String, Object>> items = posts.stream().map(row -> {
            Map<String, Object> payload = PostPayloads.publicFrom(row, defaultProfileImageUrl);
            var poll = pollsByPostId.get(row.id);
            if (poll != null) payload.put("poll", PollPayloads.from(poll));
            return payload;
        }).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (nextCursor != null) body.put("next_cursor", nextCursor);
        return body;
    }
}
