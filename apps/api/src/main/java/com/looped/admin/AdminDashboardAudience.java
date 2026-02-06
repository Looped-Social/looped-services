package com.looped.admin;

import java.util.Locale;

public enum AdminDashboardAudience {
    BOTH,
    PUBLIC,
    ANON;

    public static AdminDashboardAudience parseOrDefault(String raw) {
        if (raw == null || raw.isBlank()) return BOTH;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "both" -> BOTH;
            case "public" -> PUBLIC;
            case "anon" -> ANON;
            default -> null;
        };
    }

    public String wireValue() {
        return switch (this) {
            case BOTH -> "both";
            case PUBLIC -> "public";
            case ANON -> "anon";
        };
    }
}

