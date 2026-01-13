package com.looped.admin;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/sectors")
@Validated
public class AdminSectorsController {
    private final AdminAuthService auth;
    private final CommunitiesRepository communities;
    private final AdminAuditRepository audit;
    private final CommunityVerificationsRepository verifications;

    public AdminSectorsController(AdminAuthService auth,
                                  CommunitiesRepository communities,
                                  AdminAuditRepository audit,
                                  CommunityVerificationsRepository verifications) {
        this.auth = auth;
        this.communities = communities;
        this.audit = audit;
        this.verifications = verifications;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(value = "query", required = false) String query,
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
        if (query != null && !query.isBlank()) {
            rows = communities.searchByKind("sector", query.trim(), cursorTs, cursorId, lim);
        } else {
            rows = communities.listByKind("sector", cursorTs, cursorId, lim);
        }
        String next = null;
        if (rows.size() == lim) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        var memberCounts = verifications.countActiveVerifiedMembersByCommunityIds(rows.stream().map(r -> r.id).toList());
        List<Map<String, Object>> items = rows.stream()
                .map(row -> payload(row, memberCounts.getOrDefault(row.id, 0)))
                .toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (next != null) body.put("next_cursor", next);
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,
                                    @Valid @RequestBody CreateSectorRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        String name = normalizeName(body.name());
        if (name == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "name_required"));
        }
        String description = normalizeDescription(body.description());
        String imageUrl = normalizeDescription(body.imageUrl());
        Integer ttlDays = body.verificationTtlDays();
        if (ttlDays != null && ttlDays < 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_ttl_days"));
        }
        if (communities.findByKindAndName("sector", name).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "community_exists"));
        }
        long id = communities.insert("sector", name, description, imageUrl, ttlDays);
        audit.log(authRes.admin().id, "sector.create", "community", id, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.CREATE_COMMUNITY);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var community = communities.findById(id);
        if (community.isEmpty() || !"sector".equalsIgnoreCase(community.get().kind)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        boolean deleted = communities.delete(id);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        audit.log(authRes.admin().id, "sector.delete", "community", id, null);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    private Map<String, Object> payload(CommunitiesRepository.CommunityRow row, int memberCount) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.id);
        map.put("kind", row.kind);
        map.put("name", row.name);
        if (row.description != null) map.put("description", row.description);
        map.put("member_count", memberCount);
        if (row.imageUrl != null) map.put("image_url", row.imageUrl);
        map.put("created_at", row.createdAt);
        if (row.verificationTtlDays != null) map.put("verification_ttl_days", row.verificationTtlDays);
        return map;
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

    public record CreateSectorRequest(@NotBlank String name, String description, String imageUrl,
                                      Integer verificationTtlDays) {}
}
