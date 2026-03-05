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
        long fallbackCooldown = specializationProps.getDefaultJoinCooldownMonths();
        long months = settings.findLong(AppSettingsKeys.SPECIALIZATIONS_DEFAULT_JOIN_COOLDOWN_MONTHS).orElse(fallbackCooldown);

        long fallbackMaxField = specializationProps.getDefaultMaxJoinsField();
        long maxField = settings.findLong(AppSettingsKeys.SPECIALIZATIONS_MAX_JOINS_FIELD).orElse(fallbackMaxField);
        return ResponseEntity.ok(Map.of(
                "default_join_cooldown_months", months,
                "max_joins_field", maxField
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
        boolean hasCooldown = body != null && body.defaultJoinCooldownMonths() != null;
        boolean hasMaxMajor = body != null && body.maxJoinsMajor() != null;
        boolean hasMaxField = body != null && body.maxJoinsField() != null;
        if (hasMaxMajor) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "major_not_supported",
                    "message", "maxJoinsMajor is no longer supported"
            ));
        }
        if (!hasCooldown && !hasMaxMajor && !hasMaxField) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "no_changes"));
        }
        StringBuilder meta = new StringBuilder();
        Map<String, Object> out = new java.util.HashMap<>();

        if (hasCooldown) {
            long months = body.defaultJoinCooldownMonths();
            if (months < 1 || months > 120) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "invalid_default_join_cooldown_months",
                        "message", "defaultJoinCooldownMonths must be between 1 and 120"
                ));
            }
            settings.upsertLong(AppSettingsKeys.SPECIALIZATIONS_DEFAULT_JOIN_COOLDOWN_MONTHS, months, authRes.admin().id);
            out.put("default_join_cooldown_months", months);
            meta.append("default_join_cooldown_months=").append(months);
        }

        if (hasMaxField) {
            long max = body.maxJoinsField();
            if (max < 1 || max > 20) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "invalid_max_joins_field",
                        "message", "maxJoinsField must be between 1 and 20"
                ));
            }
            settings.upsertLong(AppSettingsKeys.SPECIALIZATIONS_MAX_JOINS_FIELD, max, authRes.admin().id);
            out.put("max_joins_field", max);
            if (meta.length() > 0) meta.append(",");
            meta.append("max_joins_field=").append(max);
        }

        audit.log(authRes.admin().id, "settings.specializations.update", "settings", null,
                meta.length() == 0 ? null : meta.toString());
        return ResponseEntity.ok(out);
    }

    public record UpdateSpecializationsSettingsRequest(
            @JsonProperty("defaultJoinCooldownMonths") Long defaultJoinCooldownMonths,
            @JsonProperty("maxJoinsMajor") Long maxJoinsMajor,
            @JsonProperty("maxJoinsField") Long maxJoinsField
    ) {}
}
