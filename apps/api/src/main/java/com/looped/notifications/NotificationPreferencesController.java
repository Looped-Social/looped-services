package com.looped.notifications;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/notifications/preferences")
public class NotificationPreferencesController {
    private final NotificationPreferencesService service;

    public NotificationPreferencesController(NotificationPreferencesService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt) {
        var res = service.get(jwt.getSubject());
        if (res.status() == NotificationPreferencesService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        return ResponseEntity.ok(Map.of("notifications", res.preferences().toMap()));
    }

    @PutMapping
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,
                                    @RequestBody(required = false) NotificationPreferencesUpdate body) {
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_body"));
        }
        var res = service.update(jwt.getSubject(), body);
        if (res.status() == NotificationPreferencesService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        return ResponseEntity.ok(Map.of("notifications", res.preferences().toMap()));
    }
}
