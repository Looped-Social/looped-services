package com.looped.admin;

import java.util.Locale;
import java.util.Set;

public final class AdminStatuses {
    public static final String ACTIVE = "active";
    public static final String DISABLED = "disabled";

    public static final Set<String> ALL = Set.of(ACTIVE, DISABLED);

    private AdminStatuses() {}

    public static String normalize(String status) {
        if (status == null) return null;
        String trimmed = status.trim();
        if (trimmed.isBlank()) return null;
        return trimmed.toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String status) {
        String normalized = normalize(status);
        return normalized != null && ALL.contains(normalized);
    }
}
