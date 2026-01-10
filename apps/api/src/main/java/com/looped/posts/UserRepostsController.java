package com.looped.posts;

import com.looped.polls.PollPayloads;
import com.looped.polls.PollsService;
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
public class UserRepostsController {
    private final PostCollectionsService service;
    private final PollsService pollsService;

    public UserRepostsController(PostCollectionsService service, PollsService pollsService) {
        this.service = service;
        this.pollsService = pollsService;
    }

    @GetMapping("/v1/users/{id}/reposts")
    public ResponseEntity<?> reposts(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.repostedForUser(jwt.getSubject(), id, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing reposts"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found"
            ));
            case OK -> {
                Long viewerPrincipalId = pollsService.viewerPrincipalId(jwt.getSubject());
                List<Long> postIds = res.posts().stream().map(p -> p.id).toList();
                var pollsByPostId = pollsService.viewsByPostId(viewerPrincipalId, postIds);
                List<Map<String, Object>> items = res.posts().stream().map(row -> {
                    Map<String, Object> payload = PostPayloads.from(row);
                    var poll = pollsByPostId.get(row.id);
                    if (poll != null) payload.put("poll", PollPayloads.from(poll));
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

