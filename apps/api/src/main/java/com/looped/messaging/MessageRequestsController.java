package com.looped.messaging;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/message-requests")
public class MessageRequestsController {
    private final MessageRequestService service;

    public MessageRequestsController(MessageRequestService service) {
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
        if (res.status() == MessageRequestService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == MessageRequestService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() != MessageRequestService.Status.OK) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("items", res.items());
        if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id
    ) {
        var res = service.approve(jwt.getSubject(), id);
        if (res.status() == MessageRequestService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == MessageRequestService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() == MessageRequestService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (res.status() == MessageRequestService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        return ResponseEntity.ok(Map.of("status", res.requestStatus()));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id
    ) {
        var res = service.reject(jwt.getSubject(), id);
        if (res.status() == MessageRequestService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == MessageRequestService.Status.ANONYMOUS_NOT_ALLOWED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "anonymous_not_allowed"));
        }
        if (res.status() == MessageRequestService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (res.status() == MessageRequestService.Status.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        return ResponseEntity.ok(Map.of("status", res.requestStatus()));
    }
}
