package com.looped.users;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class UsersSearchQuery {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    private UsersSearchQuery() {}

    static String toPrefixTsquery(String raw) {
        if (raw == null) return null;
        var matcher = TOKEN.matcher(raw.toLowerCase(Locale.ROOT));
        List<String> parts = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() < 2) continue;
            parts.add(token + ":*");
            if (parts.size() >= 8) break;
        }
        if (parts.isEmpty()) return null;
        return String.join(" & ", parts);
    }
}

