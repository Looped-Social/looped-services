package com.looped.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.looped.shared.Pagination;
import com.looped.settings.AppConfigService;
import com.looped.auth.FirebaseAdminService;
import com.looped.users.ProfileImageUrls;
import com.looped.users.UserBanRepository;
import com.looped.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin")
public class AdminUsersController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(AdminUsersController.class);

    private final AdminAuthService auth;
    private final UserRepository users;
    private final UserBanRepository bans;
    private final AdminUserStatsRepository stats;
    private final AdminAuditRepository audit;
    private final AppConfigService appConfig;
    private final AdminUsersRepository adminUsers;
    private final FirebaseAdminService firebaseAdmin;

    public AdminUsersController(AdminAuthService auth, UserRepository users, UserBanRepository bans,
                                AdminUserStatsRepository stats, AdminAuditRepository audit, AppConfigService appConfig,
                                AdminUsersRepository adminUsers, FirebaseAdminService firebaseAdmin) {
        this.auth = auth;
        this.users = users;
        this.bans = bans;
        this.stats = stats;
        this.audit = audit;
        this.appConfig = appConfig;
        this.adminUsers = adminUsers;
        this.firebaseAdmin = firebaseAdmin;
    }

    @GetMapping("/users")
    public ResponseEntity<?> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "banned", required = false) Boolean banned,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.BAN_USER);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Missing admin permission: " + AdminPermissions.BAN_USER
            ));
        }
        String normalizedSort = sort == null ? "created_at_desc" : sort.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalizedSort.isBlank()) normalizedSort = "created_at_desc";
        if (!"created_at_desc".equals(normalizedSort)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_sort",
                    "message", "sort must be created_at_desc"
            ));
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
        int lim = Math.max(1, Math.min(limit, 200));
        boolean bannedOnly = banned != null && banned;
        List<UserRepository.UserRow> rows = bannedOnly
                ? users.adminSearchBanned(query, cursorTs, cursorId, lim)
                : users.adminSearchAll(query, cursorTs, cursorId, lim);
        String next = null;
        if (rows.size() == lim) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        List<ObjectNode> items = rows.stream().map(u -> {
            ObjectNode node = JSON.createObjectNode();
            node.put("id", u.id);
            node.put("handle", u.handle);
            if (u.email == null) node.putNull("email"); else node.put("email", u.email);
            if (u.companyId == null) node.putNull("company_id"); else node.put("company_id", u.companyId);
            node.putPOJO("created_at", u.createdAt);
            node.put("account_status", accountStatus(u));
            if (u.disabledAt == null) node.putNull("disabled_at"); else node.putPOJO("disabled_at", u.disabledAt);
            if (u.disabledReason == null) node.putNull("disabled_reason"); else node.put("disabled_reason", u.disabledReason);
            if (u.deletedAt == null) node.putNull("deleted_at"); else node.putPOJO("deleted_at", u.deletedAt);

            ObjectNode banNode = JSON.createObjectNode();
            banNode.put("status", "none");
            node.set("ban", banNode);
            var ban = bans.findActiveByUserId(u.id);
            ban.ifPresent(b -> {
                ObjectNode activeBanNode = JSON.createObjectNode();
                activeBanNode.put("status", "banned");
                if (b.reason == null) activeBanNode.putNull("reason"); else activeBanNode.put("reason", b.reason);
                activeBanNode.putPOJO("created_at", b.createdAt);
                if (b.expiresAt == null) activeBanNode.putNull("expires_at"); else activeBanNode.putPOJO("expires_at", b.expiresAt);
                node.set("ban", activeBanNode);
            });
            return node;
        }).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        if (next != null) body.put("next_cursor", next);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.BAN_USER);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Missing admin permission: " + AdminPermissions.BAN_USER
            ));
        }
        var userOpt = users.findByIdIncludingDeleted(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "User not found"
            ));
        }
        var user = userOpt.get();
        Map<String, Object> body = new HashMap<>();
        body.put("id", user.id);
        body.put("firebase_uid", user.firebaseUid);
        body.put("handle", user.handle);
        body.put("email", user.email);
        body.put("company_id", user.companyId);
        body.put("display_name", user.displayName);
        body.put("bio", user.bio);
        body.put("profile_image_url", ProfileImageUrls.resolve(user.profileImageUrl, appConfig.defaultProfileImageUrl()));
        body.put("created_at", user.createdAt);
        body.put("account_status", accountStatus(user));
        body.put("disabled_at", user.disabledAt);
        body.put("disabled_reason", user.disabledReason);
        body.put("deleted_at", user.deletedAt);
        body.put("deleted_by", user.deletedBy);
        Map<String, Object> banMap = new HashMap<>();
        banMap.put("status", "none");
        banMap.put("reason", null);
        banMap.put("created_at", null);
        banMap.put("expires_at", null);
        banMap.put("created_by", null);
        try {
            var ban = bans.findActiveByUserId(user.id);
            ban.ifPresent(b -> {
                banMap.put("status", "banned");
                banMap.put("reason", b.reason);
                banMap.put("created_at", b.createdAt);
                banMap.put("expires_at", b.expiresAt);
                banMap.put("created_by", b.createdBy);
            });
        } catch (RuntimeException ex) {
            log.warn("Failed to load active ban for admin user detail userId={}", user.id, ex);
        }
        body.put("ban", banMap);

        var moderationStats = stats.forUser(user.id);
        if (moderationStats == null) {
            moderationStats = stats.emptyStats();
        }
        Map<String, Object> statsMap = new HashMap<>();
        statsMap.put("posts_total", moderationStats.postsTotal);
        statsMap.put("posts_removed_total", moderationStats.postsRemovedTotal);
        statsMap.put("reports_against_user_total", moderationStats.reportsAgainstUserTotal);
        statsMap.put("reports_against_user_open", moderationStats.reportsAgainstUserOpen);
        statsMap.put("reports_against_user_resolved", moderationStats.reportsAgainstUserResolved);
        statsMap.put("reports_against_user_dismissed", moderationStats.reportsAgainstUserDismissed);
        statsMap.put("reports_against_posts_total", moderationStats.reportsAgainstPostsTotal);
        statsMap.put("reports_against_posts_open", moderationStats.reportsAgainstPostsOpen);
        statsMap.put("reports_against_posts_resolved", moderationStats.reportsAgainstPostsResolved);
        statsMap.put("reports_against_posts_dismissed", moderationStats.reportsAgainstPostsDismissed);
        statsMap.put("reports_filed_total", moderationStats.reportsFiledTotal);
        statsMap.put("reports_filed_open", moderationStats.reportsFiledOpen);
        statsMap.put("reports_filed_resolved", moderationStats.reportsFiledResolved);
        statsMap.put("reports_filed_dismissed", moderationStats.reportsFiledDismissed);
        body.put("moderation_stats", statsMap);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/users/{id}/ban")
    public ResponseEntity<?> ban(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @Validated @RequestBody BanRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.BAN_USER);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Missing admin permission: " + AdminPermissions.BAN_USER
            ));
        }
        var userOpt = users.findByIdIncludingDeleted(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "User not found"
            ));
        }
        OffsetDateTime expiresAt = null;
        if (body.expiresAt() != null && !body.expiresAt().isBlank()) {
            try {
                expiresAt = OffsetDateTime.parse(body.expiresAt());
            } catch (DateTimeParseException e) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", "invalid_expires_at",
                        "message", "expires_at must be ISO-8601"
                ));
            }
        } else if (body.durationSeconds() != null && body.durationSeconds() > 0) {
            expiresAt = OffsetDateTime.now().plusSeconds(body.durationSeconds());
        }
        bans.revokeActive(id, authRes.admin().id);
        long banId = bans.banUser(id, authRes.admin().id, body.reason(), expiresAt);
        audit.log(authRes.admin().id, "user.ban", "user", id, null);
        Map<String, Object> resp = new HashMap<>();
        resp.put("id", banId);
        resp.put("status", "banned");
        resp.put("expires_at", expiresAt);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/users/{id}/unban")
    public ResponseEntity<?> unban(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.BAN_USER);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Missing admin permission: " + AdminPermissions.BAN_USER
            ));
        }
        var userOpt = users.findByIdIncludingDeleted(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "User not found"
            ));
        }
        bans.revokeActive(id, authRes.admin().id);
        audit.log(authRes.admin().id, "user.unban", "user", id, null);
        return ResponseEntity.ok(Map.of("status", "active"));
    }

    @PostMapping("/users/{id}/disable")
    public ResponseEntity<?> disable(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) DisableRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.BAN_USER);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Missing admin permission: " + AdminPermissions.BAN_USER
            ));
        }
        if (body == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_body",
                    "message", "Request body is required"
            ));
        }
        String reason = body.reason() == null ? null : body.reason().trim();
        if (reason == null || reason.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_reason",
                    "message", "reason is required"
            ));
        }
        if (reason.length() > 500) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_reason",
                    "message", "reason is too long"
            ));
        }
        var userOpt = users.findByIdIncludingDeleted(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "User not found"
            ));
        }
        var user = userOpt.get();
        if (user.firebaseUid != null && adminUsers.findByFirebaseUid(user.firebaseUid).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "cannot_disable_admin",
                    "message", "Cannot disable an admin account"
            ));
        }
        if (user.deletedAt != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "invalid_state",
                    "message", "Cannot disable a deleted account"
            ));
        }
        var updatedOpt = users.adminDisable(id, authRes.admin().id, reason);
        if (updatedOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "User not found"
            ));
        }
        var updated = updatedOpt.get();
        audit.log(authRes.admin().id, "user.disable", "user", id, "{\"reason\":\"" + escapeJson(reason) + "\"}");
        if (updated.firebaseUid != null && firebaseAdmin.isEnabled()) {
            firebaseAdmin.setDisabled(updated.firebaseUid, true);
            firebaseAdmin.revokeRefreshTokens(updated.firebaseUid);
        }
        return ResponseEntity.ok(Map.of(
                "id", updated.id,
                "account_status", "disabled",
                "disabled_at", updated.disabledAt,
                "disabled_reason", updated.disabledReason
        ));
    }

    @PostMapping("/users/{id}/enable")
    public ResponseEntity<?> enable(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) EnableRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.BAN_USER);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Missing admin permission: " + AdminPermissions.BAN_USER
            ));
        }
        var userOpt = users.findByIdIncludingDeleted(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "User not found"
            ));
        }
        var user = userOpt.get();
        if (user.firebaseUid != null && adminUsers.findByFirebaseUid(user.firebaseUid).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "cannot_enable_admin",
                    "message", "Cannot enable an admin account"
            ));
        }
        if (user.deletedAt != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "invalid_state",
                    "message", "Cannot enable a deleted account"
            ));
        }
        String note = body == null ? null : body.reason();
        if (note != null) note = note.trim();
        if (note != null && note.length() > 500) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_reason",
                    "message", "reason is too long"
            ));
        }
        var updatedOpt = users.adminEnable(id);
        if (updatedOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "User not found"
            ));
        }
        var updated = updatedOpt.get();
        audit.log(authRes.admin().id, "user.enable", "user", id,
                note == null || note.isBlank() ? null : "{\"reason\":\"" + escapeJson(note) + "\"}");
        if (updated.firebaseUid != null && firebaseAdmin.isEnabled()) {
            firebaseAdmin.setDisabled(updated.firebaseUid, false);
            firebaseAdmin.revokeRefreshTokens(updated.firebaseUid);
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("id", updated.id);
        resp.put("account_status", "active");
        resp.put("disabled_at", null);
        resp.put("disabled_reason", null);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/users/{id}/delete")
    public ResponseEntity<?> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) DeleteUserRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.BAN_USER);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Missing admin permission: " + AdminPermissions.BAN_USER
            ));
        }
        if (body == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_body",
                    "message", "Request body is required"
            ));
        }
        if (body.confirm() == null || body.confirm().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "missing_confirm",
                    "message", "confirm must be provided"
            ));
        }
        if (!"DELETE".equals(body.confirm().trim())) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_confirm",
                    "message", "confirm must equal DELETE"
            ));
        }
        String mode = body.mode() == null ? "soft" : body.mode().trim().toLowerCase(java.util.Locale.ROOT);
        if (!"soft".equals(mode)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "delete_not_supported",
                    "message", "Only soft delete is supported"
            ));
        }
        String reason = body.reason() == null ? null : body.reason().trim();
        if (reason == null || reason.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_reason",
                    "message", "reason is required"
            ));
        }
        if (reason.length() > 500) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_reason",
                    "message", "reason is too long"
            ));
        }
        var userOpt = users.findByIdIncludingDeleted(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "User not found"
            ));
        }
        var user = userOpt.get();
        if (user.firebaseUid != null && adminUsers.findByFirebaseUid(user.firebaseUid).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "cannot_delete_admin",
                    "message", "Cannot delete an admin account"
            ));
        }
        var updatedOpt = users.adminSoftDelete(id, authRes.admin().id, reason);
        if (updatedOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "User not found"
            ));
        }
        var updated = updatedOpt.get();
        audit.log(authRes.admin().id, "user.delete", "user", id,
                "{\"mode\":\"soft\",\"reason\":\"" + escapeJson(reason) + "\"}");
        if (updated.firebaseUid != null && firebaseAdmin.isEnabled()) {
            firebaseAdmin.setDisabled(updated.firebaseUid, true);
            firebaseAdmin.revokeRefreshTokens(updated.firebaseUid);
        }
        return ResponseEntity.ok(Map.of(
                "id", updated.id,
                "account_status", "deleted",
                "deleted_at", updated.deletedAt
        ));
    }

    public record BanRequest(@JsonProperty("duration_seconds") Long durationSeconds,
                             @JsonProperty("expires_at") String expiresAt,
                             String reason) {}

    public record DisableRequest(String reason, @JsonProperty("notify_user") Boolean notifyUser) {}

    public record EnableRequest(String reason) {}

    public record DeleteUserRequest(String reason, String mode, String confirm) {}

    private static String accountStatus(UserRepository.UserRow user) {
        if (user == null) return "active";
        if (user.deletedAt != null) return "deleted";
        if (user.disabledAt != null) return "disabled";
        return "active";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
