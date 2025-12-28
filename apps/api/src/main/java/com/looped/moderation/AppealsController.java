package com.looped.moderation;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/appeals")
public class AppealsController {
    private final AppealService service;

    public AppealsController(AppealService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @Validated @RequestBody CreateRequest body) {
        var res = service.create(jwt.getSubject(), body.targetType(), body.targetId(), body.reason());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case INVALID_TARGET_TYPE -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_target_type"
            ));
            case INVALID_TARGET -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_target"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found"
            ));
            case NOT_REMOVED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "post_not_removed"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden"
            ));
            case DUPLICATE -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "appeal_already_open"
            ));
            case NO_ACTIVE_BAN -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "no_active_ban"
            ));
            case OK -> new ResponseEntity<>(Map.of("id", res.id()), HttpStatus.CREATED);
        };
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(value = "status", required = false) String status) {
        var res = service.list(jwt.getSubject(), status);
        if (res.status() == AppealService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
        }
        List<Map<String, Object>> items = res.items().stream().map(r -> Map.<String, Object>of(
                "id", r.id,
                "target_type", r.targetType,
                "target_id", r.targetId,
                "reason", r.reason,
                "status", r.status,
                "created_at", r.createdAt,
                "updated_at", r.updatedAt,
                "reviewed_at", r.reviewedAt,
                "reviewed_by", r.reviewedBy,
                "reviewed_reason", r.reviewedReason
        )).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    public record CreateRequest(@NotBlank String targetType, Long targetId, @NotBlank String reason) {}
}
