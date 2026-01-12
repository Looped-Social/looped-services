package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.CommunityDomainsRepository;
import com.looped.communities.CommunityLogoResolver;
import com.looped.shared.Pagination;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    private final AdminAuditRepository audit;
    private final CommunityVerificationsRepository verifications;

    public AdminCommunitiesController(AdminAuthService auth,
                                      CommunitiesRepository communities,
                                      CommunityDomainsRepository domains,
                                      CommunityLogoResolver logos,
                                      AdminAuditRepository audit,
                                      CommunityVerificationsRepository verifications) {
        this.auth = auth;
        this.communities = communities;
        this.domains = domains;
        this.logos = logos;
        this.audit = audit;
        this.verifications = verifications;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(value = "query", required = false) String query,
                                  @RequestParam(value = "kind", required = false) String kind,
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
        List<CommunitiesRepository.CommunityRow> rows;
        String normalizedKind = kind != null ? normalizeKind(kind) : null;
        String specializationType = kind != null ? normalizeSpecializationTypeFromKind(kind) : null;
        if (kind != null && normalizedKind == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_kind"));
        }
        boolean hasQuery = query != null && !query.isBlank();
        if (normalizedKind != null) {
            rows = hasQuery
                    ? (specializationType == null
                        ? communities.searchByKind(normalizedKind, query.trim(), cursorTs, cursorId, lim)
                        : communities.searchByKindAndSpecializationType(normalizedKind, specializationType, query.trim(), cursorTs, cursorId, lim))
                    : (specializationType == null
                        ? communities.listByKind(normalizedKind, cursorTs, cursorId, lim)
                        : communities.listByKindAndSpecializationType(normalizedKind, specializationType, cursorTs, cursorId, lim));
        } else {
            rows = hasQuery
                    ? communities.search(query.trim(), cursorTs, cursorId, lim)
                    : communities.list(cursorTs, cursorId, lim);
        }
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
        Integer ttlDays = body.verificationTtlDays();
        if (ttlDays != null && ttlDays < 1) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_ttl_days"));
        }
        if (communities.findByKindAndName(kind, name, specializationType).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_exists"));
        }
        long id = communities.insert(kind, name, description, imageUrl, ttlDays, specializationType, shortName);
        audit.log(authRes.admin().id, "community.create", "community", id, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
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
        Integer ttlDays = body.verificationTtlDays();
        if (ttlDays != null && ttlDays < 1) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_ttl_days"));
        }
        boolean descriptionProvided = body.description() != null;
        String description = normalizeDescription(body.description());
        boolean shortNameProvided = body.shortName() != null;
        String shortName = normalizeShortName(body.shortName());
        if (!descriptionProvided && ttlDays == null && !shortNameProvided) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "no_changes"));
        }
        boolean updated = communities.updateDetails(id, descriptionProvided, description, ttlDays, shortNameProvided, shortName);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        StringBuilder meta = new StringBuilder();
        if (descriptionProvided) meta.append("description_updated");
        if (ttlDays != null) {
            if (meta.length() > 0) meta.append(",");
            meta.append("verification_ttl_days=").append(ttlDays);
        }
        if (shortNameProvided) {
            if (meta.length() > 0) meta.append(",");
            meta.append("short_name_updated");
        }
        audit.log(authRes.admin().id, "community.update", "community", id,
                meta.length() == 0 ? null : meta.toString());
        Map<String, Object> out = new HashMap<>();
        out.put("id", id);
        if (descriptionProvided) out.put("description", description);
        if (ttlDays != null) out.put("verification_ttl_days", ttlDays);
        if (shortNameProvided) out.put("short_name", shortName);
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
        String resolved = row.imageUrl;
        if ((resolved == null || resolved.isBlank()) && fallbacks != null) {
            resolved = fallbacks.get(row.id);
        } else if (resolved == null || resolved.isBlank()) {
            resolved = logos.resolve(row.id, row.kind, row.imageUrl);
        }
        if (resolved != null && !resolved.isBlank()) map.put("image_url", resolved);
        map.put("created_at", row.createdAt);
        if (row.verificationTtlDays != null) map.put("verification_ttl_days", row.verificationTtlDays);
        return map;
    }

    private String normalizeKind(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (normalized.equals("profession") || normalized.equals("proffesion")) {
            normalized = "sector";
        }
        if (normalized.equals("major") || normalized.equals("department")) {
            normalized = "specialization";
        }
        if (!normalized.equals("company") && !normalized.equals("school") && !normalized.equals("sector") && !normalized.equals("specialization")) {
            return null;
        }
        return normalized;
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
        if (!normalized.equals("major") && !normalized.equals("department")) return null;
        return normalized;
    }

    private String normalizeSpecializationTypeFromKind(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("major") || normalized.equals("department")) return normalized;
        return null;
    }

    public record CreateCommunityRequest(@NotBlank String kind, @NotBlank String name, String description, String imageUrl,
                                         Integer verificationTtlDays, String specializationType, String shortName) {}

    public record UpdateCommunityRequest(String description, Integer verificationTtlDays, String shortName) {}
}
