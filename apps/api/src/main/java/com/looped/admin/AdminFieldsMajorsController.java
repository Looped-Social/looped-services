package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.SpecializationIcons;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/v1/admin")
@Validated
public class AdminFieldsMajorsController {
    private final AdminAuthService auth;
    private final CommunitiesRepository communities;
    private final AdminAuditRepository audit;

    private final boolean sfSymbolsEnabled;
    private final Set<String> sfSymbolAllowlist;
    private final boolean imageUrlEnabled;
    private final String imageUrlAllowedPrefix;

    public AdminFieldsMajorsController(AdminAuthService auth,
                                       CommunitiesRepository communities,
                                       AdminAuditRepository audit,
                                       @Value("${specializations.icons.sf-symbol.enabled:false}") boolean sfSymbolsEnabled,
                                       @Value("${specializations.icons.sf-symbol.allowlist:}") String sfSymbolAllowlistCsv,
                                       @Value("${specializations.icons.image-url.enabled:false}") boolean imageUrlEnabled,
                                       @Value("${specializations.icons.image-url.allowed-prefix:}") String imageUrlAllowedPrefix) {
        this.auth = auth;
        this.communities = communities;
        this.audit = audit;
        this.sfSymbolsEnabled = sfSymbolsEnabled;
        this.sfSymbolAllowlist = parseCsvSet(sfSymbolAllowlistCsv);
        this.imageUrlEnabled = imageUrlEnabled;
        this.imageUrlAllowedPrefix = imageUrlAllowedPrefix;
    }

    @PatchMapping("/fields/{fieldId}")
    public ResponseEntity<?> updateField(@AuthenticationPrincipal Jwt jwt,
                                         @PathVariable("fieldId") long fieldId,
                                         @Valid @RequestBody UpdateSpecializationRequest body) {
        return updateSpecialization(jwt, fieldId, "field", body);
    }

    @PatchMapping("/majors/{majorId}")
    public ResponseEntity<?> updateMajor(@AuthenticationPrincipal Jwt jwt,
                                         @PathVariable("majorId") long majorId,
                                         @Valid @RequestBody UpdateSpecializationRequest body) {
        return updateSpecialization(jwt, majorId, "major", body);
    }

    private ResponseEntity<?> updateSpecialization(Jwt jwt,
                                                   long id,
                                                   String specializationType,
                                                   UpdateSpecializationRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }

        boolean nameProvided = body != null && body.name() != null;
        boolean iconProvided = body != null && body.icon() != null;
        if (!nameProvided && !iconProvided) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "no_changes"));
        }

        String normalizedName = null;
        if (nameProvided) {
            normalizedName = body.name().trim();
            if (normalizedName.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "invalid_name",
                        "message", "name must not be blank"
                ));
            }
            if (normalizedName.length() > 200) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "invalid_name",
                        "message", "name is too long"
                ));
            }
            var existingByName = communities.findByKindAndName("specialization", normalizedName, specializationType);
            if (existingByName.isPresent() && existingByName.get().id != id) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "specialization_exists",
                        "message", "A " + specializationType + " specialization with that name already exists"
                ));
            }
        }

        String iconKind = null;
        String iconValue = null;
        if (iconProvided) {
            try {
                SpecializationIcons.NormalizedIcon icon = SpecializationIcons.normalizeAndValidateForWrite(
                        body.icon(),
                        sfSymbolsEnabled,
                        sfSymbolAllowlist,
                        imageUrlEnabled,
                        imageUrlAllowedPrefix
                );
                if (icon != null && icon.isClear()) {
                    iconKind = "emoji";
                    iconValue = null;
                } else if (icon != null) {
                    iconKind = icon.kind();
                    iconValue = icon.value();
                }
            } catch (SpecializationIcons.IconValidationException e) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", e.error(),
                        "message", e.getMessage()
                ));
            }
        }

        boolean updated = communities.updateSpecializationIconAndName(
                id,
                specializationType,
                nameProvided,
                normalizedName,
                iconProvided,
                iconKind,
                iconValue
        );
        if (!updated) {
            var existing = communities.findById(id);
            if (existing.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            }
            String kind = existing.get().kind == null ? "" : existing.get().kind.trim().toLowerCase(Locale.ROOT);
            String t = existing.get().specializationType == null ? "" : existing.get().specializationType.trim().toLowerCase(Locale.ROOT);
            if (!"specialization".equals(kind) || !specializationType.equals(t)) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "invalid_specialization",
                        "message", "id must be a " + specializationType + " specialization"
                ));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        StringBuilder meta = new StringBuilder();
        meta.append("specialization_type=").append(specializationType);
        if (nameProvided) meta.append(",name_updated");
        if (iconProvided) meta.append(",icon_updated");
        audit.log(authRes.admin().id, "specialization.update", "specialization", id, meta.toString());

        Map<String, Object> out = new java.util.HashMap<>();
        out.put("id", id);
        if (nameProvided) out.put("name", normalizedName);
        if (iconProvided) {
            Map<String, Object> icon = com.looped.communities.SpecializationIcons.payloadOrNull(iconKind, iconValue);
            out.put("icon", icon);
        }
        return ResponseEntity.ok(out);
    }

    private Set<String> parseCsvSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : csv.split(",")) {
            if (part == null) continue;
            String v = part.trim();
            if (!v.isBlank()) out.add(v);
        }
        return Set.copyOf(out);
    }

    public record UpdateSpecializationRequest(String name, SpecializationIcons.IconRequest icon) {}
}
