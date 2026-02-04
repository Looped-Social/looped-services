package com.looped.admin;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AdminPermissions {
    public static final String MANAGE_ADMINS = "manage_admins";
    public static final String BAN_USER = "ban_user";
    public static final String VIEW_POSTS = "view_posts";
    public static final String REMOVE_POST = "remove_post";
    public static final String CREATE_COMMUNITY = "create_community";
    public static final String VIEW_REPORTS = "view_reports";
    public static final String RESOLVE_REPORTS = "resolve_reports";
    public static final String VERIFY_USERS = "verify_users";
    public static final String DELETE_MEDIA = "delete_media";
    public static final String VIEW_FEEDBACK = "view_feedback";
    public static final String SEND_ANNOUNCEMENTS = "send_announcements";
    public static final String VIEW_MODERATION_QUEUE = "view_moderation_queue";
    public static final String RESOLVE_MODERATION_QUEUE = "resolve_moderation_queue";
    public static final String MANAGE_MODERATION_BLOCKLIST = "manage_moderation_blocklist";

    public static final Set<String> ALL = Set.of(
            MANAGE_ADMINS,
            BAN_USER,
            VIEW_POSTS,
            REMOVE_POST,
            CREATE_COMMUNITY,
            VIEW_REPORTS,
            RESOLVE_REPORTS,
            VERIFY_USERS,
            DELETE_MEDIA,
            VIEW_FEEDBACK,
            SEND_ANNOUNCEMENTS,
            VIEW_MODERATION_QUEUE,
            RESOLVE_MODERATION_QUEUE,
            MANAGE_MODERATION_BLOCKLIST
    );

    private AdminPermissions() {}

    public static List<String> normalize(List<String> raw) {
        if (raw == null) return null;
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) continue;
            String val = item.trim().toLowerCase(Locale.ROOT);
            if (!val.isBlank()) normalized.add(val);
        }
        return List.copyOf(normalized);
    }

    public static boolean areValid(List<String> permissions) {
        if (permissions == null) return false;
        for (String permission : permissions) {
            if (!ALL.contains(permission)) return false;
        }
        return true;
    }

    public static List<String> defaultsForRole(String role) {
        String normalized = AdminRoles.normalize(role);
        if (AdminRoles.OWNER.equals(normalized)) {
            return List.of(
                    MANAGE_ADMINS,
                    BAN_USER,
                    VIEW_POSTS,
                    REMOVE_POST,
                    CREATE_COMMUNITY,
                    VIEW_REPORTS,
                    RESOLVE_REPORTS,
                    VERIFY_USERS,
                    DELETE_MEDIA,
                    VIEW_FEEDBACK,
                    SEND_ANNOUNCEMENTS,
                    VIEW_MODERATION_QUEUE,
                    RESOLVE_MODERATION_QUEUE,
                    MANAGE_MODERATION_BLOCKLIST
            );
        }
        if (AdminRoles.ADMIN.equals(normalized)) {
            return List.of(
                    BAN_USER,
                    VIEW_POSTS,
                    REMOVE_POST,
                    CREATE_COMMUNITY,
                    VIEW_REPORTS,
                    RESOLVE_REPORTS,
                    VERIFY_USERS,
                    DELETE_MEDIA,
                    VIEW_FEEDBACK,
                    SEND_ANNOUNCEMENTS,
                    VIEW_MODERATION_QUEUE,
                    RESOLVE_MODERATION_QUEUE,
                    MANAGE_MODERATION_BLOCKLIST
            );
        }
        if (AdminRoles.MODERATOR.equals(normalized)) {
            return List.of(
                    BAN_USER,
                    VIEW_POSTS,
                    REMOVE_POST,
                    CREATE_COMMUNITY,
                    VIEW_REPORTS,
                    RESOLVE_REPORTS,
                    VIEW_FEEDBACK,
                    VIEW_MODERATION_QUEUE,
                    RESOLVE_MODERATION_QUEUE,
                    MANAGE_MODERATION_BLOCKLIST
            );
        }
        return List.of();
    }

    public static boolean hasPermission(AdminUsersRepository.AdminUserRow admin, String permission) {
        if (admin == null || permission == null) return false;
        String normalized = permission.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return false;
        return admin.permissions != null && admin.permissions.contains(normalized);
    }
}
