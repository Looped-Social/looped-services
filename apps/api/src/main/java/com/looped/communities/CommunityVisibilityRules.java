package com.looped.communities;

import java.util.Locale;

public final class CommunityVisibilityRules {
    private CommunityVisibilityRules() {}

    public static boolean isSchoolKind(String kind) {
        if (kind == null || kind.isBlank()) return false;
        return "school".equals(kind.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isSpecializationKind(String kind) {
        if (kind == null || kind.isBlank()) return false;
        return "specialization".equals(kind.trim().toLowerCase(Locale.ROOT));
    }

    public static String normalizeSpecializationType(String specializationType) {
        if (specializationType == null || specializationType.isBlank()) return null;
        String normalized = specializationType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "field", "major" -> normalized;
            default -> null;
        };
    }

    public static boolean isMajorSpecialization(String kind, String specializationType) {
        return isSpecializationKind(kind) && "major".equals(normalizeSpecializationType(specializationType));
    }

    public static boolean isFieldSpecialization(String kind, String specializationType) {
        return isSpecializationKind(kind) && "field".equals(normalizeSpecializationType(specializationType));
    }

    public static boolean isUserVisible(String kind, String specializationType) {
        if (isSchoolKind(kind)) return false;
        return !isMajorSpecialization(kind, specializationType);
    }
}
