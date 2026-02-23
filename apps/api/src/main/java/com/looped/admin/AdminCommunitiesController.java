package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityRequestAvailabilityNotifier;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.CommunityDomainsRepository;
import com.looped.communities.CommunityImageSlots;
import com.looped.communities.CommunityLogoResolver;
import com.looped.communities.SpecializationIcons;
import com.looped.shared.Pagination;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/communities")
@Validated
public class AdminCommunitiesController {
    private final AdminAuthService auth;
    private final CommunitiesRepository communities;
    private final CommunityDomainsRepository domains;
    private final CommunityLogoResolver logos;
    private final CommunityRequestAvailabilityNotifier availabilityNotifier;
    private final AdminAuditRepository audit;
    private final CommunityVerificationsRepository verifications;
    private final boolean sfSymbolsEnabled;
    private final java.util.Set<String> sfSymbolAllowlist;
    private final boolean imageUrlEnabled;
    private final String imageUrlAllowedPrefix;

    public AdminCommunitiesController(AdminAuthService auth,
                                      CommunitiesRepository communities,
                                      CommunityDomainsRepository domains,
                                      CommunityLogoResolver logos,
                                      CommunityRequestAvailabilityNotifier availabilityNotifier,
                                      AdminAuditRepository audit,
                                      CommunityVerificationsRepository verifications,
                                      @Value("${specializations.icons.sf-symbol.enabled:false}") boolean sfSymbolsEnabled,
                                      @Value("${specializations.icons.sf-symbol.allowlist:}") String sfSymbolAllowlistCsv,
                                      @Value("${specializations.icons.image-url.enabled:false}") boolean imageUrlEnabled,
                                      @Value("${specializations.icons.image-url.allowed-prefix:}") String imageUrlAllowedPrefix) {
        this.auth = auth;
        this.communities = communities;
        this.domains = domains;
        this.logos = logos;
        this.availabilityNotifier = availabilityNotifier;
        this.audit = audit;
        this.verifications = verifications;
        this.sfSymbolsEnabled = sfSymbolsEnabled;
        this.sfSymbolAllowlist = parseCsvSet(sfSymbolAllowlistCsv);
        this.imageUrlEnabled = imageUrlEnabled;
        this.imageUrlAllowedPrefix = imageUrlAllowedPrefix;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(value = "query", required = false) String query,
                                  @RequestParam(value = "kind", required = false) String kind,
                                  @RequestParam(value = "kinds", required = false) String kinds,
                                  @RequestParam(value = "cursor", required = false) String cursor,
                                  @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        OffsetDateTime cursorTs = null;
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cursorTs = decoded.timestamp();
                cursorId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        List<CommunitiesRepository.KindFilter> kindFilters = parseKindFilters(kind, kinds);
        if (kindFilters == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_kind"));
        }
        boolean hasQuery = query != null && !query.isBlank();
        String normalizedQuery = hasQuery ? query.trim() : null;
        List<CommunitiesRepository.CommunityRow> rows = hasQuery
                ? communities.searchByKindFilters(kindFilters, normalizedQuery, cursorTs, cursorId, lim)
                : communities.listByKindFilters(kindFilters, cursorTs, cursorId, lim);
        long totalCount = hasQuery
                ? communities.countSearchByKindFilters(kindFilters, normalizedQuery)
                : communities.countByKindFilters(kindFilters);
        String next = null;
        if (rows.size() == lim) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        var fallback = logos.resolveFallbacks(rows.stream()
                .map(row -> new CommunityLogoResolver.CommunityRef(row.id, row.kind, row.imageUrl))
                .toList());
        var domainFallbacks = domains.firstDomainsForCommunities(rows.stream().map(row -> row.id).toList());
        var memberCounts = verifications.countActiveVerifiedMembersByCommunityIds(rows.stream().map(r -> r.id).toList());
        List<Map<String, Object>> items = rows.stream()
                .map(row -> payload(row, fallback, domainFallbacks, memberCounts.getOrDefault(row.id, 0)))
                .toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        body.put("total_count", totalCount);
        if (next != null) body.put("next_cursor", next);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var row = communities.findById(id);
        if (row.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        var firstDomain = domains.firstDomain(id).orElse(null);
        Map<Long, String> domainFallbacks = firstDomain == null ? null : Map.of(id, firstDomain);
        int memberCount = verifications.countActiveVerifiedMembers(id);
        return ResponseEntity.ok(payload(row.get(), null, domainFallbacks, memberCount));
    }

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,
                                    @Valid @RequestBody CreateCommunityRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        String kind = normalizeKind(body.kind());
        if (kind == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_kind"));
        }
        String specializationType = normalizeSpecializationType(body.specializationType());
        if ("specialization".equals(kind) && specializationType == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "specialization_type_required"));
        }
        String name = normalizeName(body.name());
        if (name == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "name_required"));
        }
        String description = normalizeDescription(body.description());
        String imageUrl = normalizeDescription(body.imageUrl());
        String shortName = normalizeShortName(body.shortName());
        List<String> normalizedDomains = normalizeDomains(body.domains());
        if (normalizedDomains == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_domain"));
        }
        if ("specialization".equals(kind) && !normalizedDomains.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "domains_not_allowed_for_specialization",
                    "message", "major/field communities cannot define verification domains"
            ));
        }
        Integer ttlDays = body.verificationTtlDays();
        if (ttlDays != null && ttlDays < 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_ttl_days"));
        }
        Integer cooldownMonths = body.specializationJoinCooldownMonths();
        if (cooldownMonths != null && cooldownMonths < 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_cooldown_months"));
        }
        if (cooldownMonths != null) {
            if (!"specialization".equals(kind) || specializationType == null) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "invalid_specialization",
                        "message", "specializationJoinCooldownMonths can only be set for major/field specializations"
                ));
            }
            if (cooldownMonths == 0) cooldownMonths = null;
        }
        if (communities.findByKindAndName(kind, name, specializationType).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_exists"));
        }
        long id = communities.insert(kind, name, description, imageUrl, ttlDays, specializationType, shortName, cooldownMonths);
        for (String domain : normalizedDomains) {
            domains.insert(id, domain);
        }
        String requestKind = requestKindForCommunity(kind, specializationType);
        var notificationSummary = availabilityNotifier.notifyForCreatedCommunity(requestKind, name, id);
        audit.log(authRes.admin().id, "community.create", "community", id, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", id,
                "matched_requests", notificationSummary.matchedRequests(),
                "notified_requests", notificationSummary.sentEmails()
        ));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable("id") long id,
                                    @Valid @RequestBody UpdateCommunityRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        boolean nameProvided = body != null && body.name() != null;
        String name = nameProvided ? normalizeName(body.name()) : null;
        if (nameProvided && name == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "name_required"));
        }
        boolean iconProvided = body != null && body.icon() != null;
        Integer ttlDays = body.verificationTtlDays();
        if (ttlDays != null && ttlDays < 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_ttl_days"));
        }
        Integer cooldownMonths = body.specializationJoinCooldownMonths();
        if (cooldownMonths != null && cooldownMonths < 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_cooldown_months"));
        }
        CommunitiesRepository.CommunityRow existingRow = null;
        String existingSpecializationType = null;
        if (cooldownMonths != null || iconProvided || nameProvided) {
            var existing = communities.findById(id);
            if (existing.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            }
            existingRow = existing.get();
            String kind = existingRow.kind == null ? "" : existingRow.kind.trim().toLowerCase(Locale.ROOT);
            String specializationType = existingRow.specializationType == null ? "" : existingRow.specializationType.trim().toLowerCase(Locale.ROOT);
            existingSpecializationType = specializationType;
            if (cooldownMonths != null) {
                if (!"specialization".equals(kind) || (!"major".equals(specializationType) && !"field".equals(specializationType))) {
                    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                            "error", "invalid_specialization",
                            "message", "specializationJoinCooldownMonths can only be set for major/field specializations"
                    ));
                }
            }
            if (iconProvided) {
                if (!"specialization".equals(kind) || (!"major".equals(specializationType) && !"field".equals(specializationType))) {
                    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                            "error", "invalid_specialization",
                            "message", "icon can only be set for majors/fields"
                    ));
                }
            }
        }
        boolean descriptionProvided = body.description() != null;
        String description = normalizeDescription(body.description());
        boolean shortNameProvided = body.shortName() != null;
        String shortName = normalizeShortName(body.shortName());
        boolean effectiveNameChange = nameProvided && existingRow != null && !equalsIgnoreCase(existingRow.name, name);
        if (!descriptionProvided && ttlDays == null && !shortNameProvided && cooldownMonths == null && !iconProvided && !effectiveNameChange) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "no_changes"));
        }
        boolean detailsUpdated = true;
        if (descriptionProvided || ttlDays != null || shortNameProvided || cooldownMonths != null) {
            detailsUpdated = communities.updateDetails(id, descriptionProvided, description, ttlDays, shortNameProvided, shortName, cooldownMonths);
            if (!detailsUpdated) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            }
        }
        boolean nameUpdated = false;
        String oldName = null;
        if (effectiveNameChange) {
            if (existingRow == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
            oldName = existingRow.name;
            String kind = existingRow.kind == null ? "" : existingRow.kind.trim().toLowerCase(Locale.ROOT);
            String specializationType = existingRow.specializationType == null ? null : existingRow.specializationType.trim().toLowerCase(Locale.ROOT);

            if ("specialization".equals(kind)) {
                if (specializationType == null || specializationType.isBlank()) {
                    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_specialization"));
                }
                var conflict = communities.findByKindAndName("specialization", name, specializationType);
                if (conflict.isPresent() && conflict.get().id != id) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_exists"));
                }
                nameUpdated = communities.updateSpecializationIconAndName(
                        id,
                        specializationType,
                        true,
                        name,
                        false,
                        null,
                        null
                );
            } else {
                var conflict = communities.findByKindAndName(kind, name);
                if (conflict.isPresent() && conflict.get().id != id) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_exists"));
                }
                nameUpdated = communities.updateNameNonSpecialization(id, name);
            }
            if (!nameUpdated) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            }
        }
        String updatedIconKind = null;
        String updatedIconValue = null;
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
                    updatedIconKind = "emoji";
                    updatedIconValue = null;
                } else if (icon != null) {
                    updatedIconKind = icon.kind();
                    updatedIconValue = icon.value();
                }
            } catch (SpecializationIcons.IconValidationException e) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", e.error(),
                        "message", e.getMessage()
                ));
            }
            if (existingSpecializationType == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
            boolean iconUpdated = communities.updateSpecializationIconAndName(
                    id,
                    existingSpecializationType,
                    false,
                    null,
                    true,
                    updatedIconKind,
                    updatedIconValue
            );
            if (!iconUpdated) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            }
        }
        StringBuilder meta = new StringBuilder();
        if (descriptionProvided) meta.append("description_updated");
        if (ttlDays != null) {
            if (meta.length() > 0) meta.append(",");
            meta.append("verification_ttl_days=").append(ttlDays);
        }
        if (cooldownMonths != null) {
            if (meta.length() > 0) meta.append(",");
            meta.append("specialization_join_cooldown_months=").append(cooldownMonths);
        }
        if (shortNameProvided) {
            if (meta.length() > 0) meta.append(",");
            meta.append("short_name_updated");
        }
        if (nameUpdated) {
            if (meta.length() > 0) meta.append(",");
            meta.append("name_updated");
        }
        if (iconProvided) {
            if (meta.length() > 0) meta.append(",");
            meta.append("icon_updated");
        }
        String auditMeta = null;
        if (nameUpdated) {
            auditMeta = "{\"name\":{\"old\":\"" + escapeJson(oldName) + "\",\"new\":\"" + escapeJson(name) + "\"}"
                    + (meta.length() == 0 ? "" : ",\"fields\":\"" + escapeJson(meta.toString()) + "\"")
                    + "}";
        } else if (meta.length() > 0) {
            auditMeta = meta.toString();
        }
        audit.log(authRes.admin().id, "community.update", "community", id, auditMeta);
        Map<String, Object> out = new HashMap<>();
        out.put("id", id);
        if (descriptionProvided) out.put("description", description);
        if (ttlDays != null) out.put("verification_ttl_days", ttlDays);
        if (cooldownMonths != null) out.put("specialization_join_cooldown_months", cooldownMonths == 0 ? null : cooldownMonths);
        if (shortNameProvided) out.put("short_name", shortName);
        if (nameUpdated) out.put("name", name);
        if (iconProvided) {
            Map<String, Object> icon = SpecializationIcons.payloadOrNull(updatedIconKind, updatedIconValue);
            out.put("icon", icon);
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/change-kind")
    public ResponseEntity<?> changeKind(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable("id") long id,
                                        @Valid @RequestBody ChangeKindRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var existingOpt = communities.findById(id);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        var existing = existingOpt.get();
        String currentKind = existing.kind == null ? null : existing.kind.trim().toLowerCase(Locale.ROOT);
        String currentSpecType = existing.specializationType == null ? null : existing.specializationType.trim().toLowerCase(Locale.ROOT);

        KindTarget target = normalizeKindTarget(body);
        if (target == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_kind"));
        }
        if (!isAllowedKindTransition(currentKind, currentSpecType, target.kind(), target.specializationType())) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_transition",
                    "message", "Changing community kind is only allowed among company, school, field, and major"
            ));
        }
        if (Objects.equals(currentKind, target.kind()) && Objects.equals(currentSpecType, target.specializationType())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "no_changes"));
        }

        // Uniqueness checks
        if ("specialization".equals(target.kind())) {
            var conflict = communities.findByKindAndName("specialization", existing.name, target.specializationType());
            if (conflict.isPresent() && conflict.get().id != id) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_exists"));
            }
        } else {
            var conflict = communities.findByKindAndName(target.kind(), existing.name);
            if (conflict.isPresent() && conflict.get().id != id) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_exists"));
            }
        }

        boolean updated = communities.updateKindAndSpecializationType(id, target.kind(), target.specializationType());
        if (!updated) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        String auditMeta = "{\"kind\":{\"old\":\"" + escapeJson(currentKind) + "\",\"new\":\"" + escapeJson(target.kind()) + "\"}"
                + ",\"specialization_type\":{\"old\":\"" + escapeJson(currentSpecType) + "\",\"new\":\"" + escapeJson(target.specializationType()) + "\"}"
                + "}";
        audit.log(authRes.admin().id, "community.change_kind", "community", id, auditMeta);
        Map<String, Object> out = new HashMap<>();
        out.put("id", id);
        out.put("kind", target.kind());
        if (target.specializationType() != null) out.put("specialization_type", target.specializationType());
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        boolean deleted = communities.delete(id);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        audit.log(authRes.admin().id, "community.delete", "community", id, null);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    private Map<String, Object> payload(CommunitiesRepository.CommunityRow row,
                                        Map<Long, String> fallbacks,
                                        Map<Long, String> domainFallbacks,
                                        int memberCount) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.id);
        map.put("kind", row.kind);
        map.put("name", row.name);
        String resolvedShortName = row.shortName;
        if (resolvedShortName == null || resolvedShortName.isBlank()) {
            String domain = domainFallbacks == null ? null : domainFallbacks.get(row.id);
            resolvedShortName = deriveShortName(domain);
        }
        if (resolvedShortName != null && !resolvedShortName.isBlank()) {
            map.put("short_name", resolvedShortName);
        }
        if (row.description != null) map.put("description", row.description);
        map.put("member_count", memberCount);
        if (row.specializationType != null) map.put("specialization_type", row.specializationType);
        if ("specialization".equalsIgnoreCase(row.kind)) {
            Map<String, Object> icon = com.looped.communities.SpecializationIcons.payloadOrNull(row.iconKind, row.iconValue);
            if (icon != null) map.put("icon", icon);
        }
        String fallback = fallbacks != null ? fallbacks.get(row.id) : logos.resolve(row.id, row.kind, row.imageUrl);
        CommunityImageSlots.putPayload(map, row.imageUrl, row.profileImageUrl, fallback);
        map.put("created_at", row.createdAt);
        if (row.verificationTtlDays != null) map.put("verification_ttl_days", row.verificationTtlDays);
        if (row.specializationJoinCooldownMonths != null) {
            map.put("specialization_join_cooldown_months", row.specializationJoinCooldownMonths);
        }
        return map;
    }

    private String normalizeKind(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (normalized.equals("major") || normalized.equals("field")) {
            normalized = "specialization";
        }
        if (!normalized.equals("company") && !normalized.equals("school") && !normalized.equals("specialization")) {
            return null;
        }
        return normalized;
    }

    private String requestKindForCommunity(String kind, String specializationType) {
        if (kind == null) return null;
        String normalizedKind = kind.trim().toLowerCase(Locale.ROOT);
        if ("specialization".equals(normalizedKind)) {
            return normalizeSpecializationType(specializationType);
        }
        return normalizedKind;
    }

    private String normalizeName(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String normalizeDescription(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String normalizeShortName(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private List<String> normalizeDomains(List<String> rawDomains) {
        if (rawDomains == null || rawDomains.isEmpty()) return List.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String raw : rawDomains) {
            if (raw == null || raw.isBlank()) continue;
            String normalized = domains.normalizeDomain(raw);
            if (normalized == null) return null;
            out.add(normalized);
        }
        return List.copyOf(out);
    }

    private String deriveShortName(String domain) {
        if (domain == null) return null;
        String trimmed = domain.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isBlank()) return null;
        int dot = trimmed.indexOf('.');
        return dot <= 0 ? trimmed : trimmed.substring(0, dot);
    }

    private String normalizeSpecializationType(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (!normalized.equals("major") && !normalized.equals("field")) return null;
        return normalized;
    }

    private String normalizeSpecializationTypeFromKind(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("major") || normalized.equals("field")) return normalized;
        return null;
    }

    private List<CommunitiesRepository.KindFilter> parseKindFilters(String singleKind, String kindsCsv) {
        Map<String, CommunitiesRepository.KindFilter> deduped = new java.util.LinkedHashMap<>();

        if (singleKind != null && singleKind.isBlank()) return null;
        if (singleKind != null && !singleKind.isBlank()) {
            CommunitiesRepository.KindFilter parsed = parseKindFilter(singleKind);
            if (parsed == null) return null;
            deduped.put(kindFilterKey(parsed), parsed);
        }

        if (kindsCsv != null && !kindsCsv.isBlank()) {
            boolean sawToken = false;
            for (String raw : kindsCsv.split(",")) {
                if (raw == null) continue;
                String token = raw.trim();
                if (token.isBlank()) continue;
                sawToken = true;
                CommunitiesRepository.KindFilter parsed = parseKindFilter(token);
                if (parsed == null) return null;
                deduped.put(kindFilterKey(parsed), parsed);
            }
            if (!sawToken) return null;
        }

        if (deduped.isEmpty()) return List.of();
        return List.copyOf(deduped.values());
    }

    private CommunitiesRepository.KindFilter parseKindFilter(String raw) {
        String normalizedKind = normalizeKind(raw);
        if (normalizedKind == null) return null;
        String specializationType = normalizeSpecializationTypeFromKind(raw);
        return new CommunitiesRepository.KindFilter(normalizedKind, specializationType);
    }

    private String kindFilterKey(CommunitiesRepository.KindFilter filter) {
        if (filter == null) return "";
        String kind = filter.kind() == null ? "" : filter.kind();
        String spec = filter.specializationType() == null ? "" : filter.specializationType();
        return kind + ":" + spec;
    }

    public record CreateCommunityRequest(@NotBlank String kind, @NotBlank String name, String description, String imageUrl,
                                         Integer verificationTtlDays, String specializationType, String shortName,
                                         Integer specializationJoinCooldownMonths, List<String> domains) {}

    public record UpdateCommunityRequest(String name, String description, Integer verificationTtlDays, String shortName,
                                         Integer specializationJoinCooldownMonths, SpecializationIcons.IconRequest icon) {}

    public record ChangeKindRequest(@NotNull @NotBlank String kind, String specializationType) {}

    private record KindTarget(String kind, String specializationType) {}

    private KindTarget normalizeKindTarget(ChangeKindRequest body) {
        if (body == null || body.kind() == null) return null;
        String raw = body.kind().trim().toLowerCase(Locale.ROOT);
        if (raw.isBlank()) return null;
        if (raw.equals("major") || raw.equals("field")) {
            return new KindTarget("specialization", raw);
        }
        if (raw.equals("specialization")) {
            String st = normalizeSpecializationType(body.specializationType());
            return st == null ? null : new KindTarget("specialization", st);
        }
        if (raw.equals("company") || raw.equals("school") || raw.equals("sector")) {
            return new KindTarget(raw, null);
        }
        return null;
    }

    private boolean isAllowedKindTransition(String fromKind,
                                           String fromSpecType,
                                           String toKind,
                                           String toSpecType) {
        if (fromKind == null || toKind == null) return false;
        if (!isAllowedAdminCommunityKind(fromKind, fromSpecType)) return false;
        if (!isAllowedAdminCommunityKind(toKind, toSpecType)) return false;
        return true;
    }

    private boolean isAllowedAdminCommunityKind(String kind, String specializationType) {
        if (kind == null) return false;
        if ("company".equals(kind) || "school".equals(kind)) return true;
        if (!"specialization".equals(kind)) return false;
        return "field".equals(specializationType) || "major".equals(specializationType);
    }

    private java.util.Set<String> parseCsvSet(String csv) {
        if (csv == null || csv.isBlank()) return java.util.Set.of();
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : csv.split(",")) {
            if (part == null) continue;
            String v = part.trim();
            if (!v.isBlank()) out.add(v);
        }
        return java.util.Set.copyOf(out);
    }

    private boolean equalsIgnoreCase(String a, String b) {
        if (a == null) return b == null;
        return b != null && a.equalsIgnoreCase(b);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
