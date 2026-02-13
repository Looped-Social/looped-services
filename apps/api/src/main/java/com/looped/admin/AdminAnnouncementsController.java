package com.looped.admin;

import com.looped.notifications.NotificationPublisher;
import com.looped.shared.Pagination;
import com.looped.users.UserRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/announcements")
public class AdminAnnouncementsController {
    private final AdminAuthService auth;
    private final AdminAuditRepository audit;
    private final AdminAnnouncementsRepository announcements;
    private final UserRepository users;
    private final NotificationPublisher notifications;

    public AdminAnnouncementsController(AdminAuthService auth,
                                        AdminAuditRepository audit,
                                        AdminAnnouncementsRepository announcements,
                                        UserRepository users,
                                        NotificationPublisher notifications) {
        this.auth = auth;
        this.audit = audit;
        this.announcements = announcements;
        this.users = users;
        this.notifications = notifications;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(value = "scope", required = false) String scope,
                                  @RequestParam(value = "companyId", required = false) Long companyId,
                                  @RequestParam(value = "cursor", required = false) String cursor,
                                  @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.check(jwt.getSubject(), email);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        boolean canView = AdminPermissions.hasPermission(authRes.admin(), AdminPermissions.SEND_ANNOUNCEMENTS)
                || AdminPermissions.hasPermission(authRes.admin(), AdminPermissions.SEND_GLOBAL_ANNOUNCEMENTS);
        if (!canView) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }

        String normalizedScope = scope == null ? "all" : scope.trim().toLowerCase(Locale.ROOT);
        if (!List.of("all", "company", "global").contains(normalizedScope)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_scope"));
        }
        if ("global".equals(normalizedScope) && companyId != null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_company_filter"));
        }
        int lim = Math.max(1, Math.min(limit, 200));
        OffsetDateTime cursorTs = null;
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cursorTs = decoded.timestamp();
                cursorId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        var rows = announcements.list(normalizedScope, companyId, cursorTs, cursorId, lim);
        String next = null;
        if (rows.size() == lim) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }

        List<Map<String, Object>> items = rows.stream().map(row -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", row.id);
            item.put("scope", row.scope);
            if (row.companyId != null) item.put("company_id", row.companyId);
            if (row.companyName != null) item.put("company_name", row.companyName);
            item.put("title", row.title);
            item.put("body", row.body);
            if (row.deeplink != null) item.put("deeplink", row.deeplink);
            item.put("sent", row.sentCount);
            if (row.actorAdminId != null) item.put("actor_admin_id", row.actorAdminId);
            item.put("created_at", row.createdAt);
            return item;
        }).toList();

        Map<String, Object> bodyOut = new HashMap<>();
        bodyOut.put("items", items);
        if (next != null) bodyOut.put("next_cursor", next);
        return ResponseEntity.ok(bodyOut);
    }

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,
                                    @Validated @RequestBody CreateRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.SEND_ANNOUNCEMENTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var userIds = users.listActiveUserIdsByCompany(body.companyId());
        Map<String, Object> payload = buildPayload(body.title(), body.body(), body.deeplink(), body.companyId());
        notifications.notifyAnnouncement(userIds, payload);
        announcements.insert(
                authRes.admin().id,
                "company",
                body.companyId(),
                body.title(),
                body.body(),
                normalizeOptional(body.deeplink()),
                userIds.size()
        );
        audit.log(authRes.admin().id, "announcement.send", "company", body.companyId(), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("sent", userIds.size()));
    }

    @PostMapping("/global")
    public ResponseEntity<?> createGlobal(@AuthenticationPrincipal Jwt jwt,
                                          @Validated @RequestBody GlobalCreateRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.SEND_GLOBAL_ANNOUNCEMENTS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var userIds = users.listActiveUserIds();
        Map<String, Object> payload = buildPayload(body.title(), body.body(), body.deeplink(), null);
        notifications.notifyAnnouncement(userIds, payload);
        announcements.insert(
                authRes.admin().id,
                "global",
                null,
                body.title(),
                body.body(),
                normalizeOptional(body.deeplink()),
                userIds.size()
        );
        audit.log(authRes.admin().id, "announcement.send_global", "global", null, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("sent", userIds.size()));
    }

    private Map<String, Object> buildPayload(String title, String body, String deeplink, Long companyId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        if (deeplink != null && !deeplink.isBlank()) payload.put("deeplink", deeplink);
        if (companyId != null) payload.put("company_id", companyId);
        return payload;
    }

    private String normalizeOptional(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    public record CreateRequest(
            @NotNull Long companyId,
            @NotBlank @Size(max = 140) String title,
            @NotBlank @Size(max = 1000) String body,
            @Size(max = 300) String deeplink
    ) {}

    public record GlobalCreateRequest(
            @NotBlank @Size(max = 140) String title,
            @NotBlank @Size(max = 1000) String body,
            @Size(max = 300) String deeplink
    ) {}
}
