package com.looped.messaging;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/messages")
public class MessagingSearchController {
    private final MessagingSearchService service;

    public MessagingSearchController(MessagingSearchService service) {
        this.service = service;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("query") String query,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        query = normalizeQueryParam(query);
        if (query == null || query.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "query_required",
                    "message", "query must be provided"
            ));
        }
        if (query.trim().length() < 2) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "query_too_short",
                    "message", "query must be at least 2 characters"
            ));
        }
        int lim = Math.max(1, Math.min(limit, 50));
        var res = service.search(jwt.getSubject(), query, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case ANONYMOUS_NOT_ALLOWED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "anonymous_not_allowed"
            ));
            case OK -> {
                Map<String, Object> body = new HashMap<>();
                body.put("items", res.items());
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
        };
    }

    private static String normalizeQueryParam(String raw) {
        if (raw == null) return null;
        if (!raw.contains("%") && !raw.contains("+")) return raw;
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return raw;
        }
    }
}
