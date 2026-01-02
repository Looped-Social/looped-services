package com.looped.communities;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

final class CommunityFollowCursor {
    private static final String VERSION = "v2";

    private CommunityFollowCursor() {}

    record Cursor(int pinnedRank, int sortRank, int sortOrderValue, OffsetDateTime lastActivity, long followId) {}

    static String encode(CommunityFollowsRepository.FollowRow row) {
        int pinnedRank = row.isPinned ? 0 : 1;
        int sortRank = row.sortOrder == null ? 1 : 0;
        int sortOrderValue = row.sortOrder == null ? Integer.MAX_VALUE : row.sortOrder;
        OffsetDateTime lastActivity = row.lastActivity != null ? row.lastActivity : row.followedAt;
        String raw = VERSION + "|" + pinnedRank + "|" + sortRank + "|" + sortOrderValue + "|" + lastActivity + "|" + row.followId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String cursor) throws IllegalArgumentException {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 6 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("bad cursor");
            }
            int pinnedRank = Integer.parseInt(parts[1]);
            int sortRank = Integer.parseInt(parts[2]);
            int sortOrderValue = Integer.parseInt(parts[3]);
            OffsetDateTime lastActivity = OffsetDateTime.parse(parts[4]);
            long followId = Long.parseLong(parts[5]);
            return new Cursor(pinnedRank, sortRank, sortOrderValue, lastActivity, followId);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bad cursor");
        }
    }

    static Cursor tryDecode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            return decode(cursor);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
