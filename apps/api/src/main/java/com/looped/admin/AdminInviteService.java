package com.looped.admin;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class AdminInviteService {
    private static final Duration DEFAULT_TTL = Duration.ofDays(7);

    private final AdminAuthService auth;
    private final AdminUsersRepository admins;
    private final AdminInvitesRepository invites;

    public AdminInviteService(AdminAuthService auth, AdminUsersRepository admins, AdminInvitesRepository invites) {
        this.auth = auth;
        this.admins = admins;
        this.invites = invites;
    }

    public CreateInviteResult createInvite(String firebaseUid, String actorEmail, String inviteeEmail, String role, List<String> rawPermissions) {
        var authRes = auth.requirePermission(firebaseUid, actorEmail, AdminPermissions.MANAGE_ADMINS);
        if (authRes.status() == AdminAuthService.Status.NOT_ADMIN) {
            return CreateInviteResult.notAdmin();
        }
        if (authRes.status() == AdminAuthService.Status.FORBIDDEN) {
            return CreateInviteResult.forbidden();
        }
        String normalizedEmail = normalizeEmail(inviteeEmail);
        if (normalizedEmail == null) return CreateInviteResult.badRequest("email_required");

        String normalizedRole = AdminRoles.normalize(role);
        if (normalizedRole == null || !AdminRoles.ALL.contains(normalizedRole)) {
            return CreateInviteResult.badRequest("invalid_role");
        }
        if (AdminRoles.OWNER.equals(normalizedRole)) {
            return CreateInviteResult.badRequest("owner_invites_not_allowed");
        }

        List<String> permissions;
        if (rawPermissions == null) {
            permissions = AdminPermissions.defaultsForRole(normalizedRole);
        } else {
            permissions = AdminPermissions.normalize(rawPermissions);
            if (!AdminPermissions.areValid(permissions)) {
                return CreateInviteResult.badRequest("invalid_permissions");
            }
        }

        if (admins.findByEmail(normalizedEmail).isPresent()) {
            return CreateInviteResult.conflict("admin_exists");
        }
        if (invites.findPendingByEmail(normalizedEmail).isPresent()) {
            return CreateInviteResult.conflict("invite_exists");
        }

        String token = AdminTokens.generate();
        String tokenHash = AdminTokens.hash(token);
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(DEFAULT_TTL);
        try {
            var invite = invites.insert(normalizedEmail, normalizedRole, permissions, tokenHash, expiresAt, authRes.admin().id);
            return CreateInviteResult.ok(token, invite.expiresAt, invite.role, invite.permissions);
        } catch (DataAccessException e) {
            return CreateInviteResult.conflict("invite_exists");
        }
    }

    public AcceptInviteResult acceptInvite(String firebaseUid, String email, String token) {
        if (firebaseUid == null || firebaseUid.isBlank()) return AcceptInviteResult.invalidToken();
        if (token == null || token.isBlank()) return AcceptInviteResult.invalidToken();
        if (email == null || email.isBlank()) return AcceptInviteResult.badRequest("email_required");

        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail == null) return AcceptInviteResult.badRequest("email_required");
        var existing = admins.findActiveByFirebaseUid(firebaseUid);
        if (existing.isPresent()) {
            return AcceptInviteResult.conflict("already_admin");
        }
        if (admins.findByEmail(normalizedEmail).isPresent()) {
            return AcceptInviteResult.conflict("email_in_use");
        }

        String tokenHash = AdminTokens.hash(token.trim());
        var invite = invites.findPendingByTokenHash(tokenHash);
        if (invite.isEmpty()) return AcceptInviteResult.invalidToken();
        if (AdminRoles.OWNER.equals(invite.get().role)) {
            return AcceptInviteResult.forbidden("owner_invites_not_allowed");
        }
        if (!normalizedEmail.equals(invite.get().email)) {
            return AcceptInviteResult.forbidden("email_mismatch");
        }

        try {
            long adminId = admins.insert(firebaseUid, normalizedEmail, invite.get().role, AdminStatuses.ACTIVE, invite.get().permissions);
            invites.markAccepted(invite.get().id, adminId);
            return AcceptInviteResult.ok(invite.get().role, invite.get().permissions);
        } catch (DataAccessException e) {
            return AcceptInviteResult.conflict("email_in_use");
        }
    }

    private String normalizeEmail(String email) {
        if (email == null) return null;
        String trimmed = email.trim();
        if (trimmed.isBlank()) return null;
        return trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    public enum Status { OK, NOT_ADMIN, FORBIDDEN, BAD_REQUEST, CONFLICT, INVALID_TOKEN }

    public record CreateInviteResult(Status status, String error, String token, OffsetDateTime expiresAt,
                                     String role, List<String> permissions) {
        static CreateInviteResult ok(String token, OffsetDateTime expiresAt, String role, List<String> permissions) {
            return new CreateInviteResult(Status.OK, null, token, expiresAt, role, permissions);
        }
        static CreateInviteResult notAdmin() { return new CreateInviteResult(Status.NOT_ADMIN, "not_admin", null, null, null, List.of()); }
        static CreateInviteResult forbidden() { return new CreateInviteResult(Status.FORBIDDEN, "forbidden", null, null, null, List.of()); }
        static CreateInviteResult badRequest(String error) { return new CreateInviteResult(Status.BAD_REQUEST, error, null, null, null, List.of()); }
        static CreateInviteResult conflict(String error) { return new CreateInviteResult(Status.CONFLICT, error, null, null, null, List.of()); }
    }

    public record AcceptInviteResult(Status status, String error, String role, List<String> permissions) {
        static AcceptInviteResult ok(String role, List<String> permissions) {
            return new AcceptInviteResult(Status.OK, null, role, permissions);
        }
        static AcceptInviteResult invalidToken() { return new AcceptInviteResult(Status.INVALID_TOKEN, "invalid_token", null, List.of()); }
        static AcceptInviteResult badRequest(String error) { return new AcceptInviteResult(Status.BAD_REQUEST, error, null, List.of()); }
        static AcceptInviteResult forbidden(String error) { return new AcceptInviteResult(Status.FORBIDDEN, error, null, List.of()); }
        static AcceptInviteResult conflict(String error) { return new AcceptInviteResult(Status.CONFLICT, error, null, List.of()); }
    }
}
