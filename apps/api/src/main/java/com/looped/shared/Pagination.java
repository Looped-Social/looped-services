package com.looped.shared;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

public final class Pagination {
    private Pagination() {}

    public record Cursor(java.time.OffsetDateTime timestamp, long id) {}

    public static String encode(OffsetDateTime createdAt, long id) {
        // Preserve full precision by encoding ISO-8601 timestamp + '|' + id
        String raw = createdAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) throws IllegalArgumentException {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int idx = raw.lastIndexOf('|');
            if (idx <= 0) throw new IllegalArgumentException("bad cursor");
            var ts = java.time.OffsetDateTime.parse(raw.substring(0, idx));
            long id = Long.parseLong(raw.substring(idx + 1));
            return new Cursor(ts, id);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bad cursor");
        }
    }
}
