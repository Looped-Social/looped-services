package com.looped.shared;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

public final class Pagination {
    private Pagination() {}

    public record Cursor(long epochMillis, long id) {}

    public static String encode(OffsetDateTime createdAt, long id) {
        long epoch = createdAt.toInstant().toEpochMilli();
        String raw = epoch + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) throws IllegalArgumentException {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int idx = raw.indexOf(':');
            if (idx <= 0) throw new IllegalArgumentException("bad cursor");
            long epoch = Long.parseLong(raw.substring(0, idx));
            long id = Long.parseLong(raw.substring(idx + 1));
            return new Cursor(epoch, id);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bad cursor");
        }
    }
}

