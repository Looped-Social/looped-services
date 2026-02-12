package com.looped.posts;

import com.looped.polls.PollPayloads;
import com.looped.polls.PollsService;
import com.looped.settings.AppConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/public/posts")
public class PublicPostsController {
    private final PostsService postsService;
    private final AppConfigService appConfig;
    private final PollsService pollsService;

    public PublicPostsController(PostsService postsService, AppConfigService appConfig, PollsService pollsService) {
        this.postsService = postsService;
        this.appConfig = appConfig;
        this.pollsService = pollsService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable("id") long id) {
        var res = postsService.getPublic(id);
        return switch (res.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "post_not_found",
                    "message", "Post not found"
            ));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "error", "post_unavailable",
                    "message", "Post is unavailable"
            ));
            case OK -> {
                String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
                var payload = PostPayloads.publicFrom(res.post(), defaultProfileImageUrl);
                var pollsByPostId = pollsService.viewsByPostId(null, List.of(res.post().id));
                var poll = pollsByPostId.get(res.post().id);
                if (poll != null) payload.put("poll", PollPayloads.from(poll));
                yield ResponseEntity.ok(payload);
            }
        };
    }
}
