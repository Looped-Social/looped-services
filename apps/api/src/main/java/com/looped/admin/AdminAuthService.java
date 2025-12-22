package com.looped.admin;

import org.springframework.stereotype.Service;

@Service
public class AdminAuthService {
    private final AdminUsersRepository admins;

    public AdminAuthService(AdminUsersRepository admins) {
        this.admins = admins;
    }

    public AdminCheckResult check(String firebaseUid, String email) {
        if (firebaseUid == null || firebaseUid.isBlank()) return AdminCheckResult.notAdmin();

        var admin = admins.findActiveByFirebaseUid(firebaseUid);
        if (admin.isEmpty() && email != null) {
            admin = admins.claimActiveByEmail(email, firebaseUid);
        }
        if (admin.isEmpty()) return AdminCheckResult.notAdmin();
        return AdminCheckResult.ok(admin.get());
    }

    public AdminCheckResult requirePermission(String firebaseUid, String email, String permission) {
        var res = check(firebaseUid, email);
        if (res.status() != Status.OK) return res;
        if (!AdminPermissions.hasPermission(res.admin(), permission)) {
            return AdminCheckResult.forbidden(res.admin());
        }
        return res;
    }

    public enum Status { OK, NOT_ADMIN, FORBIDDEN }

    public record AdminCheckResult(Status status, AdminUsersRepository.AdminUserRow admin) {
        static AdminCheckResult ok(AdminUsersRepository.AdminUserRow admin) { return new AdminCheckResult(Status.OK, admin); }
        static AdminCheckResult notAdmin() { return new AdminCheckResult(Status.NOT_ADMIN, null); }
        static AdminCheckResult forbidden(AdminUsersRepository.AdminUserRow admin) { return new AdminCheckResult(Status.FORBIDDEN, admin); }
    }
}
