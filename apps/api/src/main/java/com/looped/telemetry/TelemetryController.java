package com.looped.telemetry;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/telemetry")
public class TelemetryController {
    private static final int MAX_EVENTS_PER_REQUEST = 200;

    private final TelemetryService service;

    public TelemetryController(TelemetryService service) {
        this.service = service;
    }

    @PostMapping("/events")
    public ResponseEntity<?> events(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) TelemetryRequests.EventsRequest body) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        if (body == null || body.sessionId() == null || body.events() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_body",
                    "message", "session_id and events are required"
            ));
        }
        if (body.events().size() > MAX_EVENTS_PER_REQUEST) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                    "error", "payload_too_large",
                    "message", "Too many events"
            ));
        }
        try {
            var res = service.ingest(jwt.getSubject(), body);
            return switch (res.status()) {
                case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "user_not_provisioned"
                ));
                case OK -> new ResponseEntity<>(Map.of(
                        "status", "ok",
                        "accepted", res.accepted(),
                        "dropped", res.dropped()
                ), HttpStatus.CREATED);
            };
        } catch (DataAccessException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "telemetry_unavailable"
            ));
        }
    }
}

