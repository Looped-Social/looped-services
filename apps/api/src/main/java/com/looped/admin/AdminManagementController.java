package com.looped.admin;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin")
public class AdminManagementController {
    private final AdminAuthService auth;
    private final AdminInviteService invites;
    private final AdminUsersRepository admins;

    public AdminManagementController(AdminAuthService auth, AdminInviteService invites, AdminUsersRepository admins) {
        this.auth = auth;
        this.invites = invites;
        this.admins = admins;
    }

    @PostMapping("/invites")
    public ResponseEntity<?> createInvite(@AuthenticationPrincipal Jwt jwt, @Validated @RequestBody InviteRequest body) {
        String actorEmail = jwt.getClaimAsString("email");
        var res = invites.createInvite(jwt.getSubject(), actorEmail, body.email(), body.role(), body.permissions());
        return switch (res.status()) {
            case NOT_ADMIN, FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", res.error()));
            case BAD_REQUEST -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", res.error()));
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", res.error()));
            case OK -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "token", res.token(),
                    "expires_at", res.expiresAt(),
                    "role", res.role(),
                    "permissions", res.permissions()
            ));
            case INVALID_TOKEN -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", res.error()));
        };
    }

    @PostMapping("/invites/accept")
    public ResponseEntity<?> acceptInvite(@AuthenticationPrincipal Jwt jwt, @Validated @RequestBody AcceptInviteRequest body) {
        String email = jwt.getClaimAsString("email");
        var res = invites.acceptInvite(jwt.getSubject(), email, body.token());
        return switch (res.status()) {
            case OK -> ResponseEntity.ok(Map.of(
                    "status", "accepted",
                    "role", res.role(),
                    "permissions", res.permissions()
            ));
            case BAD_REQUEST, INVALID_TOKEN -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", res.error()));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", res.error()));
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", res.error()));
            case NOT_ADMIN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", res.error()));
        };
    }

    @GetMapping("/admins")
    public ResponseEntity<?> listAdmins(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.MANAGE_ADMINS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        List<Map<String, Object>> items = admins.listAll().stream().map(a -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", a.id);
            map.put("email", a.email);
            map.put("role", a.role);
            map.put("status", a.status);
            map.put("permissions", a.permissions);
            map.put("created_at", a.createdAt);
            map.put("last_login_at", a.lastLoginAt);
            return map;
        }).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    @PatchMapping("/admins/{id}")
    public ResponseEntity<?> updateAdmin(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @Validated @RequestBody AdminUpdateRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.MANAGE_ADMINS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }

        var existing = admins.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (AdminRoles.OWNER.equals(existing.get().role) &&
                (body.role() != null || body.status() != null || body.permissions() != null)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "owner_update_not_allowed"));
        }

        String role = body.role() != null ? AdminRoles.normalize(body.role()) : existing.get().role;
        if (role == null || !AdminRoles.ALL.contains(role)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_role"));
        }
        if (!existing.get().role.equals(AdminRoles.OWNER) && AdminRoles.OWNER.equals(role)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "owner_role_not_allowed"));
        }
        if (existing.get().role.equals(AdminRoles.OWNER) && !AdminRoles.OWNER.equals(role)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "owner_role_locked"));
        }

        String status = body.status() != null ? AdminStatuses.normalize(body.status()) : existing.get().status;
        if (status == null || !AdminStatuses.ALL.contains(status)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_status"));
        }

        List<String> permissions = existing.get().permissions;
        if (body.permissions() != null) {
            permissions = AdminPermissions.normalize(body.permissions());
            if (!AdminPermissions.areValid(permissions)) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_permissions"));
            }
        }

        if (authRes.admin().id == existing.get().id) {
            if (!AdminStatuses.ACTIVE.equals(status)) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "cannot_disable_self"));
            }
            if (!permissions.contains(AdminPermissions.MANAGE_ADMINS)) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "cannot_demote_self"));
            }
        }

        boolean updated = admins.update(existing.get().id, role, status, permissions);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "update_failed"));
        }
        return ResponseEntity.ok(Map.of(
                "id", existing.get().id,
                "role", role,
                "status", status,
                "permissions", permissions
        ));
    }

    public record InviteRequest(@NotBlank String email, @NotBlank String role, List<String> permissions) {}
    public record AcceptInviteRequest(@NotBlank String token) {}
    public record AdminUpdateRequest(String role, String status, List<String> permissions) {}
}
