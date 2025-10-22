package com.looped.moderation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/reports")
public class ModerationController {
    private final ModerationService service;

    public ModerationController(ModerationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @Validated @RequestBody CreateRequest body) {
        var res = service.create(jwt.getSubject(), body.targetType(), body.targetId(), body.reason());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case OK -> new ResponseEntity<>(Map.of("id", res.id()), HttpStatus.CREATED);
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(value = "status", required = false) String status) {
        var res = service.list(jwt.getSubject(), status);
        if (res.status() == ModerationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
        }
        List<Map<String, Object>> items = res.items().stream().map(r -> Map.of(
                "id", r.id,
                "target_type", r.targetType,
                "target_id", r.targetId,
                "reason", r.reason,
                "status", r.status,
                "created_at", r.createdAt,
                "updated_at", r.updatedAt
        )).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    @PutMapping("/{id}/resolve")
    public ResponseEntity<?> resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        var res = service.resolve(jwt.getSubject(), id);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case OK -> ResponseEntity.ok(Map.of("status", res.newStatus()));
        };
    }

    public record CreateRequest(@NotBlank String targetType, @NotNull Long targetId, @NotBlank String reason) {}
}

