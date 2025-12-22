package com.looped.admin;

import com.looped.moderation.ReportRepository;
import com.looped.shared.Pagination;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin")
public class AdminReportsController {
    private final AdminAuthService auth;
    private final ReportRepository reports;
    private final AdminAuditRepository audit;

    public AdminReportsController(AdminAuthService auth, ReportRepository reports, AdminAuditRepository audit) {
        this.auth = auth;
        this.reports = reports;
        this.audit = audit;
    }

    @GetMapping("/reports")
    public ResponseEntity<?> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VIEW_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        OffsetDateTime cursorTs = null;
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cursorTs = decoded.timestamp();
                cursorId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        OffsetDateTime fromTs = null;
        OffsetDateTime toTs = null;
        try {
            if (from != null && !from.isBlank()) {
                LocalDate start = LocalDate.parse(from);
                fromTs = start.atStartOfDay().atOffset(ZoneOffset.UTC);
            }
            if (to != null && !to.isBlank()) {
                LocalDate end = LocalDate.parse(to);
                toTs = end.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
            }
        } catch (DateTimeParseException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_date"));
        }
        String normalizedSort = sort != null ? sort.trim().toLowerCase(Locale.ROOT) : "created_at_desc";
        boolean ascending = "created_at_asc".equals(normalizedSort);
        if (!ascending && !"created_at_desc".equals(normalizedSort)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_sort"));
        }
        String normalizedStatus = status != null ? status.trim().toLowerCase(Locale.ROOT) : null;
        String normalizedTarget = targetType != null ? targetType.trim().toLowerCase(Locale.ROOT) : null;
        List<ReportRepository.ReportRow> rows = reports.listAll(
                normalizedStatus,
                normalizedTarget,
                fromTs,
                toTs,
                cursorTs,
                cursorId,
                limit,
                ascending
        );
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.id);
            map.put("target_type", r.targetType);
            map.put("target_id", r.targetId);
            map.put("reporter_id", r.reporterId);
            if (r.reporterHandle != null) map.put("reporter_handle", r.reporterHandle);
            map.put("reason", r.reason);
            map.put("status", r.status);
            map.put("created_at", r.createdAt);
            map.put("updated_at", r.updatedAt);
            if (r.resolvedAt != null) map.put("resolved_at", r.resolvedAt);
            if (r.resolvedBy != null) map.put("resolved_by", r.resolvedBy);
            if (r.resolvedReason != null) map.put("resolved_reason", r.resolvedReason);
            return map;
        }).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (next != null) body.put("next_cursor", next);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/reports/{id}/resolve")
    public ResponseEntity<?> resolve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) ResolveRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.RESOLVE_REPORTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var existing = reports.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        String reason = body != null ? body.reason() : null;
        boolean updated = reports.resolve(id, authRes.admin().id, reason);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "update_failed"));
        }
        audit.log(authRes.admin().id, "report.resolve", "report", id, null);
        return ResponseEntity.ok(Map.of("status", "resolved"));
    }

    public record ResolveRequest(String reason) {}
}
