package com.looped.comments;

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
@RequestMapping("/v1/public")
public class PublicCommentsController {
    private final CommentsService service;
    private final AppConfigService appConfig;

    public PublicCommentsController(CommentsService service, AppConfigService appConfig) {
        this.service = service;
        this.appConfig = appConfig;
    }

    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<?> list(
            @PathVariable("postId") long postId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.listPublic(postId, cursor, lim);
        return switch (res.status()) {
            case POST_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "post_not_found",
                    "message", "Post not found"
            ));
            case POST_UNAVAILABLE -> ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "error", "post_unavailable",
                    "message", "Post is unavailable"
            ));
            case OK -> {
                String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
                List<Map<String, Object>> items = res.comments().stream()
                        .map(row -> CommentPayloads.publicFrom(row, defaultProfileImageUrl))
                        .toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
        };
    }

    @GetMapping("/comments/{id}/replies")
    public ResponseEntity<?> replies(
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.repliesPublic(id, cursor, lim);
        return switch (res.status()) {
            case COMMENT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "comment_not_found",
                    "message", "Comment not found"
            ));
            case POST_UNAVAILABLE -> ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "error", "post_unavailable",
                    "message", "Post is unavailable"
            ));
            case OK -> {
                String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
                List<Map<String, Object>> items = res.comments().stream()
                        .map(row -> CommentPayloads.publicFrom(row, defaultProfileImageUrl))
                        .toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
        };
    }
}
