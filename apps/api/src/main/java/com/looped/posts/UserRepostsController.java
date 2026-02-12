package com.looped.posts;

import com.looped.polls.PollPayloads;
import com.looped.polls.PollsService;
import com.looped.settings.AppConfigService;
import org.springframework.dao.DataAccessException;
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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserRepostsController.class);

    private final PostCollectionsService service;
    private final PollsService pollsService;
    private final AppConfigService appConfig;
    private final PostViewerCapabilitiesService viewerCapabilities;

    public UserRepostsController(PostCollectionsService service,
                                 PollsService pollsService,
                                 AppConfigService appConfig,
                                 PostViewerCapabilitiesService viewerCapabilities) {
        this.service = service;
        this.pollsService = pollsService;
        this.appConfig = appConfig;
        this.viewerCapabilities = viewerCapabilities;
    }

    @GetMapping("/v1/users/me/reposts")
    public ResponseEntity<?> myReposts(
            @AuthenticationPrincipal Jwt jwt,
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
        final PostCollectionsService.ListResult res;
        try {
            res = service.reposted(jwt.getSubject(), cursor, lim);
        } catch (DataAccessException e) {
            String cause = e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage();
            log.warn("my_reposts_query_failed cause={}", cause, e);
            String requestId = org.slf4j.MDC.get("request_id");
            Map<String, Object> body = new HashMap<>();
            body.put("error", "reposts_unavailable");
            body.put("message", "Reposts are temporarily unavailable");
            if (requestId != null && !requestId.isBlank()) {
                body.put("request_id", requestId);
                body.put("requestId", requestId);
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing reposts"
            ));
            case OK -> {
                Long viewerPrincipalId = pollsService.viewerPrincipalId(jwt.getSubject());
                String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
                List<Long> postIds = res.posts().stream().map(p -> p.id).toList();
                var pollsByPostId = pollsService.viewsByPostId(viewerPrincipalId, postIds);
                var capabilitiesByPostId = viewerCapabilities.byPostId(jwt.getSubject(), res.posts(), pollsByPostId);
                List<Map<String, Object>> items = res.posts().stream().map(row -> {
                    Map<String, Object> payload = PostPayloads.from(row, defaultProfileImageUrl);
                    var poll = pollsByPostId.get(row.id);
                    if (poll != null) payload.put("poll", PollPayloads.from(poll));
                    PostPayloads.putViewerCapabilities(payload, capabilitiesByPostId.get(row.id));
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

    @GetMapping("/v1/users/{id}/reposts")
    public ResponseEntity<?> reposts(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
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
        final PostCollectionsService.ListResult res;
        try {
            res = service.repostedForUser(jwt.getSubject(), id, cursor, lim);
        } catch (DataAccessException e) {
            String cause = e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage();
            log.warn("user_reposts_query_failed cause={}", cause, e);
            String requestId = org.slf4j.MDC.get("request_id");
            Map<String, Object> body = new HashMap<>();
            body.put("error", "reposts_unavailable");
            body.put("message", "Reposts are temporarily unavailable");
            if (requestId != null && !requestId.isBlank()) {
                body.put("request_id", requestId);
                body.put("requestId", requestId);
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
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
                String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
                List<Long> postIds = res.posts().stream().map(p -> p.id).toList();
                var pollsByPostId = pollsService.viewsByPostId(viewerPrincipalId, postIds);
                var capabilitiesByPostId = viewerCapabilities.byPostId(jwt.getSubject(), res.posts(), pollsByPostId);
                List<Map<String, Object>> items = res.posts().stream().map(row -> {
                    Map<String, Object> payload = PostPayloads.from(row, defaultProfileImageUrl);
                    var poll = pollsByPostId.get(row.id);
                    if (poll != null) payload.put("poll", PollPayloads.from(poll));
                    PostPayloads.putViewerCapabilities(payload, capabilitiesByPostId.get(row.id));
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
