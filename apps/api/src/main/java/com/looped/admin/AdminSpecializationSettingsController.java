package com.looped.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.looped.communities.SpecializationProperties;
import com.looped.settings.AppSettingsKeys;
import com.looped.settings.AppSettingsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/admin/settings")
public class AdminSpecializationSettingsController {
    private final AdminAuthService auth;
    private final AppSettingsRepository settings;
    private final SpecializationProperties specializationProps;
    private final AdminAuditRepository audit;

    public AdminSpecializationSettingsController(AdminAuthService auth,
                                                 AppSettingsRepository settings,
                                                 SpecializationProperties specializationProps,
                                                 AdminAuditRepository audit) {
        this.auth = auth;
        this.settings = settings;
        this.specializationProps = specializationProps;
        this.audit = audit;
    }

    @GetMapping("/specializations")
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        long fallback = specializationProps.getDefaultJoinCooldownMonths();
        long months = settings.findLong(AppSettingsKeys.SPECIALIZATIONS_DEFAULT_JOIN_COOLDOWN_MONTHS).orElse(fallback);
        return ResponseEntity.ok(Map.of(
                "default_join_cooldown_months", months
        ));
    }

    @PatchMapping("/specializations")
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,
                                    @RequestBody UpdateSpecializationsSettingsRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (body == null || body.defaultJoinCooldownMonths() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "no_changes"));
        }
        long months = body.defaultJoinCooldownMonths();
        if (months < 1 || months > 120) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_default_join_cooldown_months",
                    "message", "defaultJoinCooldownMonths must be between 1 and 120"
            ));
        }
        settings.upsertLong(AppSettingsKeys.SPECIALIZATIONS_DEFAULT_JOIN_COOLDOWN_MONTHS, months, authRes.admin().id);
        audit.log(authRes.admin().id, "settings.specializations.update", "settings", null,
                "default_join_cooldown_months=" + months);
        return ResponseEntity.ok(Map.of("default_join_cooldown_months", months));
    }

    public record UpdateSpecializationsSettingsRequest(@JsonProperty("defaultJoinCooldownMonths") Long defaultJoinCooldownMonths) {}
}

