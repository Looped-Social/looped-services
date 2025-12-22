package com.looped.admin;

import java.util.Locale;
import java.util.Set;

public final class AdminRoles {
    public static final String OWNER = "owner";
    public static final String ADMIN = "admin";
    public static final String MODERATOR = "moderator";

    public static final Set<String> ALL = Set.of(OWNER, ADMIN, MODERATOR);

    private AdminRoles() {}

    public static String normalize(String role) {
        if (role == null) return null;
        String trimmed = role.trim();
        if (trimmed.isBlank()) return null;
        return trimmed.toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String role) {
        String normalized = normalize(role);
        return normalized != null && ALL.contains(normalized);
    }
}
