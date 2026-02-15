package com.looped.posts;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * Opaque cursor for FYP v2 (multi-pool mixing).
 *
 * Format: "fyp2." + base64url("2|asOf|patternOffset|eligibleCursor|discoveryCursor")
 *
 * Inner cursors:
 * - eligibleCursor: {@link com.looped.shared.RankPagination} cursor (or empty)
 * - discoveryCursor: {@link com.looped.shared.RankPagination} cursor (or empty)
 */
final class FypCursor {
    static final String PREFIX = "fyp2.";

    private FypCursor() {}

    record Cursor(OffsetDateTime asOf, int patternOffset, String eligibleCursor, String discoveryCursor) {}

    static String encode(Cursor c) {
        String raw = "2" +
                "|" + c.asOf +
                "|" + Math.max(0, c.patternOffset) +
                "|" + (c.eligibleCursor == null ? "" : c.eligibleCursor) +
                "|" + (c.discoveryCursor == null ? "" : c.discoveryCursor);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return PREFIX + encoded;
    }

    static Cursor decode(String cursor) throws IllegalArgumentException {
        if (cursor == null || !cursor.startsWith(PREFIX)) throw new IllegalArgumentException("bad cursor");
        String encoded = cursor.substring(PREFIX.length());
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 5) throw new IllegalArgumentException("bad cursor");
            int version = Integer.parseInt(parts[0]);
            if (version != 2) throw new IllegalArgumentException("bad cursor");
            OffsetDateTime asOf = OffsetDateTime.parse(parts[1]);
            int patternOffset = Integer.parseInt(parts[2]);
            String eligible = parts[3] == null || parts[3].isBlank() ? null : parts[3];
            String discovery = parts[4] == null || parts[4].isBlank() ? null : parts[4];
            return new Cursor(asOf, Math.max(0, patternOffset), eligible, discovery);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bad cursor");
        }
    }

    static Cursor decodeOrNull(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        if (!cursor.startsWith(PREFIX)) return null;
        try {
            return decode(cursor);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

