package com.looped.admin;

import com.looped.moderation.ModerationBlocklistRepository;
import com.looped.shared.Pagination;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
@RequestMapping("/v1/admin/moderation/blocklist")
public class AdminModerationBlocklistController {
    private final AdminAuthService auth;
    private final ModerationBlocklistRepository blocklist;
    private final AdminAuditRepository audit;

    public AdminModerationBlocklistController(AdminAuthService auth,
                                              ModerationBlocklistRepository blocklist,
                                              AdminAuditRepository audit) {
        this.auth = auth;
        this.blocklist = blocklist;
        this.audit = audit;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.MANAGE_MODERATION_BLOCKLIST);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }

        int lim = Math.max(1, Math.min(limit, 500));
        OffsetDateTime cursorTs = null;
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cursorTs = decoded.timestamp();
                cursorId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        var rows = blocklist.list(enabled, cursorTs, cursorId, lim);
        String next = null;
        if (rows.size() == lim) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.updatedAt, last.id);
        }

        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> out = new HashMap<>();
            out.put("id", r.id);
            out.put("term", r.term);
            out.put("enabled", r.enabled);
            out.put("created_at", r.createdAt);
            out.put("updated_at", r.updatedAt);
            if (r.createdBy != null) out.put("created_by", r.createdBy);
            if (r.updatedBy != null) out.put("updated_by", r.updatedBy);
            return out;
        }).toList();

        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (next != null) body.put("next_cursor", next);
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<?> add(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody AddRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.MANAGE_MODERATION_BLOCKLIST);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (body == null || body.terms() == null || body.terms().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "terms_required",
                    "message", "terms must be provided"
            ));
        }
        int max = 200;
        if (body.terms().size() > max) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "too_many_terms",
                    "message", "Provide up to " + max + " terms per request"
            ));
        }

        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (String term : body.terms()) {
            try {
                ids.add(blocklist.upsert(term, authRes.admin().id));
            } catch (IllegalArgumentException ignored) {}
        }
        audit.log(authRes.admin().id, "moderation_blocklist.add_terms", "moderation_blocklist", null, null);
        return new ResponseEntity<>(Map.of("ids", ids), HttpStatus.CREATED);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> disable(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.MANAGE_MODERATION_BLOCKLIST);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var existing = blocklist.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        boolean updated = blocklist.setEnabled(id, false, authRes.admin().id);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "update_failed"));
        }
        audit.log(authRes.admin().id, "moderation_blocklist.disable_term", "moderation_blocklist_term", id, null);
        return ResponseEntity.ok(Map.of("status", "disabled"));
    }

    public record AddRequest(List<String> terms) {}
}

