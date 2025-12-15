package com.looped.notifications;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/notifications")
public class NotificationsController {
    private final NotificationService service;

    public NotificationsController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.list(jwt.getSubject(), cursor, lim);
        if (res.status() == NotificationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() != NotificationService.Status.OK) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        List<Map<String, Object>> items = res.notifications().stream().map(this::toPayload).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<?> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        var res = service.markRead(jwt.getSubject(), id);
        if (res.status() == NotificationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == NotificationService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (res.status() == NotificationService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        return ResponseEntity.ok(Map.of("read", true));
    }

    private Map<String, Object> toPayload(NotificationRepository.NotificationRow row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.id);
        map.put("type", row.type);
        map.put("created_at", row.createdAt);
        map.put("unread", row.readAt == null);
        if (row.payload != null && !row.payload.isEmpty()) {
            map.put("payload", row.payload);
        }
        return map;
    }
}
