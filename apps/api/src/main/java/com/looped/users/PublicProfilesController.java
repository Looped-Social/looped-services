package com.looped.users;

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
@RequestMapping("/v1/public/profiles")
public class PublicProfilesController {
    private final PublicProfilesService service;
    private final PollsService pollsService;
    private final AppConfigService appConfig;

    public PublicProfilesController(PublicProfilesService service,
                                    PollsService pollsService,
                                    AppConfigService appConfig) {
        this.service = service;
        this.pollsService = pollsService;
        this.appConfig = appConfig;
    }

    @GetMapping("/{username}")
    public ResponseEntity<?> get(@PathVariable("username") String username) {
        var res = service.getByUsername(username);
        return switch (res.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "profile_not_found",
                    "message", "Profile not found"
            ));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "error", "profile_unavailable",
                    "message", "Profile is unavailable"
            ));
            case OK -> ResponseEntity.ok(res.profile());
        };
    }

    @GetMapping("/{username}/posts")
    public ResponseEntity<?> posts(
            @PathVariable("username") String username,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.postsByUsername(username, cursor, lim);
        return switch (res.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "profile_not_found",
                    "message", "Profile not found"
            ));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "error", "profile_unavailable",
                    "message", "Profile is unavailable"
            ));
            case OK -> ResponseEntity.ok(toPublicPostsPayload(res.posts(), res.nextCursor()));
        };
    }

    @GetMapping("/{username}/reposts")
    public ResponseEntity<?> reposts(
            @PathVariable("username") String username,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.repostsByUsername(username, cursor, lim);
        return switch (res.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "profile_not_found",
                    "message", "Profile not found"
            ));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "error", "profile_unavailable",
                    "message", "Profile is unavailable"
            ));
            case OK -> ResponseEntity.ok(toPublicRepostsPayload(res.reposts(), res.nextCursor()));
        };
    }

    private Map<String, Object> toPublicPostsPayload(List<com.looped.posts.PostRepository.PostRow> posts, String nextCursor) {
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

    private Map<String, Object> toPublicRepostsPayload(List<com.looped.posts.RepostsRepository.RepostedPostRow> reposts,
                                                       String nextCursor) {
        String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
        List<Long> postIds = reposts == null ? List.of() : reposts.stream().map(r -> r.post().id).toList();
        var pollsByPostId = pollsService.viewsByPostId(null, postIds);
        List<Map<String, Object>> items = reposts.stream().map(row -> {
            Map<String, Object> post = PostPayloads.publicFrom(row.post(), defaultProfileImageUrl);
            var poll = pollsByPostId.get(row.post().id);
            if (poll != null) post.put("poll", PollPayloads.from(poll));
            Map<String, Object> item = new HashMap<>();
            item.put("repost_id", row.repostId());
            item.put("reposted_at", row.repostedAt());
            item.put("post", post);
            return item;
        }).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (nextCursor != null) body.put("next_cursor", nextCursor);
        return body;
    }
}
