package com.looped.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

@Repository
public class ChannelPreferencesRepository {
    private final JdbcTemplate jdbc;

    public ChannelPreferencesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean upsertMuted(long channelId, long userId, boolean muted) {
        int rows = jdbc.update(
                "INSERT INTO channel_preferences(channel_id, user_id, muted) VALUES (?,?,?) " +
                        "ON CONFLICT (channel_id, user_id) DO UPDATE SET muted = EXCLUDED.muted, updated_at = now()",
                channelId, userId, muted
        );
        return rows > 0;
    }

    public Map<Long, Boolean> mutedByChannelIds(long userId, List<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(channelIds.size(), "?"));
        Object[] params = new Object[channelIds.size() + 1];
        params[0] = userId;
        for (int i = 0; i < channelIds.size(); i++) params[i + 1] = channelIds.get(i);
        var rows = jdbc.query(
                "SELECT channel_id, muted FROM channel_preferences WHERE user_id = ? AND channel_id IN (" + placeholders + ")",
                (rs, rowNum) -> {
                    long channelId = rs.getLong("channel_id");
                    boolean muted = rs.getBoolean("muted");
                    return java.util.Map.entry(channelId, muted);
                },
                params
        );
        Map<Long, Boolean> out = new HashMap<>();
        for (var e : rows) out.put(e.getKey(), e.getValue());
        return out;
    }

    public Set<Long> mutedUserIdsForChannel(long channelId) {
        var rows = jdbc.query(
                "SELECT user_id FROM channel_preferences WHERE channel_id = ? AND muted = true",
                (rs, rowNum) -> rs.getLong("user_id"),
                channelId
        );
        return new HashSet<>(rows);
    }
}
