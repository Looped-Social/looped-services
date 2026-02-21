package com.looped.communities;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class CommunityRequestContactEmails {
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,63}$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_CONTACT_LINE = Pattern.compile(
            "^preferred\\s+contact\\s+email\\s*:\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    private CommunityRequestContactEmails() {}

    static ParsedDescription parseLegacyContactEmailLine(String description) {
        if (description == null || description.isBlank()) {
            return new ParsedDescription(null, null);
        }
        String[] lines = description.split("\\r?\\n");
        List<String> kept = new ArrayList<>();
        String extracted = null;
        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                kept.add(line);
                continue;
            }
            var matcher = LEGACY_CONTACT_LINE.matcher(trimmed);
            if (matcher.matches()) {
                if (extracted == null) {
                    extracted = matcher.group(1);
                }
                continue;
            }
            kept.add(line);
        }
        String normalizedDescription = String.join("\n", kept).trim();
        if (normalizedDescription.isBlank()) normalizedDescription = null;
        return new ParsedDescription(normalizedDescription, extracted);
    }

    static String normalizeValidEmailOrNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isBlank()) return null;
        if (trimmed.length() > 320) return null;
        if (!EMAIL_PATTERN.matcher(trimmed).matches()) return null;
        return trimmed;
    }

    record ParsedDescription(String description, String extractedEmail) {}
}

