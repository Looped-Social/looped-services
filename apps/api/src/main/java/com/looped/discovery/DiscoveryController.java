package com.looped.discovery;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class DiscoveryController {
    private final DiscoveryService service;

    public DiscoveryController(DiscoveryService service) {
        this.service = service;
    }

    @GetMapping("/loops/search")
    public ResponseEntity<?> searchLoops(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("query") String query,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "query_required",
                    "message", "query must be provided"
            ));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.searchLoops(jwt.getSubject(), query, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before searching loops"
            ));
            case OK -> {
                List<Map<String, Object>> items = res.items().stream().map(this::loopPayload).toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
        };
    }

    @GetMapping("/hashtags/search")
    public ResponseEntity<?> searchHashtags(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("query") String query,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "query_required",
                    "message", "query must be provided"
            ));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.searchHashtags(jwt.getSubject(), query, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before searching hashtags"
            ));
            case OK -> {
                List<Map<String, Object>> items = res.items().stream().map(this::hashtagPayload).toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
        };
    }

    private Map<String, Object> loopPayload(LoopsRepository.LoopRow row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.id);
        map.put("name", row.name);
        map.put("description", row.description);
        map.put("member_count", row.memberCount);
        return map;
    }

    private Map<String, Object> hashtagPayload(HashtagsRepository.HashtagRow row) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", row.name);
        map.put("usage_count", row.usageCount);
        return map;
    }
}
