package com.looped.recommendations.people;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

final class PeopleRecommendationCursor {
    private static final String VERSION = "v1";

    private PeopleRecommendationCursor() {}

    static String encode(String rail,
                         OffsetDateTime asOf,
                         long score,
                         OffsetDateTime createdAt,
                         long userId,
                         Long communityId) {
        String communityPart = communityId == null ? "" : Long.toString(communityId);
        String raw = VERSION + "|" + rail + "|" + asOf + "|" + score + "|" + createdAt + "|" + userId + "|" + communityPart;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 7) throw new IllegalArgumentException("bad cursor");
            if (!VERSION.equals(parts[0])) throw new IllegalArgumentException("bad cursor");
            String rail = parts[1];
            OffsetDateTime asOf = OffsetDateTime.parse(parts[2]);
            long score = Long.parseLong(parts[3]);
            OffsetDateTime createdAt = OffsetDateTime.parse(parts[4]);
            long userId = Long.parseLong(parts[5]);
            Long communityId = parts[6].isBlank() ? null : Long.parseLong(parts[6]);
            return new Cursor(rail, asOf, score, createdAt, userId, communityId);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid cursor", e);
        }
    }

    record Cursor(String rail,
                  OffsetDateTime asOf,
                  long score,
                  OffsetDateTime createdAt,
                  long userId,
                  Long communityId) {}
}
