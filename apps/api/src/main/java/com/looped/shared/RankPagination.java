package com.looped.shared;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

public final class RankPagination {
    private RankPagination() {}

    public record Cursor(OffsetDateTime asOf, long score, OffsetDateTime timestamp, long id) {}

    public static String encode(OffsetDateTime asOf, long score, OffsetDateTime createdAt, long id) {
        String raw = asOf + "|" + score + "|" + createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) throws IllegalArgumentException {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int last = raw.lastIndexOf('|');
            if (last <= 0) throw new IllegalArgumentException("bad cursor");
            int third = raw.lastIndexOf('|', last - 1);
            if (third <= 0) throw new IllegalArgumentException("bad cursor");
            int second = raw.lastIndexOf('|', third - 1);
            if (second <= 0) throw new IllegalArgumentException("bad cursor");
            OffsetDateTime asOf = OffsetDateTime.parse(raw.substring(0, second));
            long score = Long.parseLong(raw.substring(second + 1, third));
            OffsetDateTime ts = OffsetDateTime.parse(raw.substring(third + 1, last));
            long id = Long.parseLong(raw.substring(last + 1));
            return new Cursor(asOf, score, ts, id);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bad cursor");
        }
    }
}
