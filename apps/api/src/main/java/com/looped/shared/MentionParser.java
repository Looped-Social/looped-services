package com.looped.shared;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MentionParser {
    private static final Pattern MENTION_PATTERN = Pattern.compile("(?<![A-Za-z0-9_])@([A-Za-z0-9_]{2,30})");

    private MentionParser() {}

    public static Set<String> extract(String content) {
        if (content == null || content.isBlank()) return Set.of();
        Matcher matcher = MENTION_PATTERN.matcher(content);
        Set<String> handles = new LinkedHashSet<>();
        while (matcher.find()) {
            String handle = matcher.group(1);
            if (handle != null && !handle.isBlank()) {
                handles.add(handle.toLowerCase(Locale.ROOT));
            }
        }
        return handles;
    }
}
