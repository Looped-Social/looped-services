package com.looped.moderation;

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
@RequestMapping("/v1/violations")
public class ViolationsController {
    private final ViolationsService service;

    public ViolationsController(ViolationsService service) {
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
        if (res.status() == ViolationsService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() != ViolationsService.Status.OK) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        List<Map<String, Object>> items = res.items().stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("target_type", row.targetType);
            map.put("target_id", row.targetId);
            map.put("reason", row.reason);
            map.put("status", row.status);
            map.put("created_at", row.createdAt);
            return map;
        }).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
        return ResponseEntity.ok(body);
    }
}
