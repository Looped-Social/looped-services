package com.looped.posts;

import com.looped.settings.AppConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/public/posts")
public class PublicPostsController {
    private final PostsService postsService;
    private final AppConfigService appConfig;

    public PublicPostsController(PostsService postsService, AppConfigService appConfig) {
        this.postsService = postsService;
        this.appConfig = appConfig;
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
                yield ResponseEntity.ok(PostPayloads.publicFrom(res.post(), defaultProfileImageUrl));
            }
        };
    }
}
