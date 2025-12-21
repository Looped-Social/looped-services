package com.looped.discovery;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HashtagParser {
    private static final Pattern TAG_PATTERN = Pattern.compile("#([A-Za-z0-9_]{1,50})");

    private HashtagParser() {}

    public static Set<String> extract(String content) {
        if (content == null || content.isBlank()) return Set.of();
        Matcher matcher = TAG_PATTERN.matcher(content);
        Set<String> tags = new LinkedHashSet<>();
        while (matcher.find()) {
            String tag = matcher.group(1);
            if (tag != null && !tag.isBlank()) {
                tags.add(tag.toLowerCase(Locale.ROOT));
            }
        }
        return tags;
    }

    public static String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.startsWith("#")) trimmed = trimmed.substring(1);
        if (trimmed.isBlank()) return null;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!TAG_PATTERN.matcher("#" + lower).matches()) return null;
        return lower;
    }
}
