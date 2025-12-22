package com.looped.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.looped.shared.Pagination;
import com.looped.users.UserBanRepository;
import com.looped.users.UserRepository;
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
    private final AdminAuthService auth;
    private final UserRepository users;
    private final UserBanRepository bans;
    private final AdminAuditRepository audit;

    public AdminUsersController(AdminAuthService auth, UserRepository users, UserBanRepository bans, AdminAuditRepository audit) {
        this.auth = auth;
        this.users = users;
        this.bans = bans;
        this.audit = audit;
    }

    @GetMapping("/users")
    public ResponseEntity<?> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.BAN_USER);
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
        List<UserRepository.UserRow> rows = users.searchAll(query, cursorTs, cursorId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        List<Map<String, Object>> items = rows.stream().map(u -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.id);
            map.put("handle", u.handle);
            map.put("email", u.email);
            map.put("company_id", u.companyId);
            map.put("created_at", u.createdAt);
            var ban = bans.findActiveByUserId(u.id);
            ban.ifPresent(b -> {
                Map<String, Object> banMap = new HashMap<>();
                banMap.put("reason", b.reason);
                banMap.put("created_at", b.createdAt);
                banMap.put("expires_at", b.expiresAt);
                map.put("ban", banMap);
            });
            return map;
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
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var userOpt = users.findByIdIncludingDeleted(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
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
        body.put("profile_image_url", user.profileImageUrl);
        body.put("created_at", user.createdAt);
        body.put("deleted_at", user.deletedAt);
        body.put("deleted_by", user.deletedBy);
        var ban = bans.findActiveByUserId(user.id);
        ban.ifPresent(b -> {
            Map<String, Object> banMap = new HashMap<>();
            banMap.put("reason", b.reason);
            banMap.put("created_at", b.createdAt);
            banMap.put("expires_at", b.expiresAt);
            banMap.put("created_by", b.createdBy);
            body.put("ban", banMap);
        });
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
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var userOpt = users.findByIdIncludingDeleted(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        OffsetDateTime expiresAt = null;
        if (body.expiresAt() != null && !body.expiresAt().isBlank()) {
            try {
                expiresAt = OffsetDateTime.parse(body.expiresAt());
            } catch (DateTimeParseException e) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_expires_at"));
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
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var userOpt = users.findByIdIncludingDeleted(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        bans.revokeActive(id, authRes.admin().id);
        audit.log(authRes.admin().id, "user.unban", "user", id, null);
        return ResponseEntity.ok(Map.of("status", "active"));
    }

    public record BanRequest(@JsonProperty("duration_seconds") Long durationSeconds,
                             @JsonProperty("expires_at") String expiresAt,
                             String reason) {}
}
