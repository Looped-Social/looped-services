package com.looped.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class ConversationPreferencesRepository {
    private final JdbcTemplate jdbc;

    public ConversationPreferencesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean upsertMuted(long conversationId, long userId, boolean muted) {
        int rows = jdbc.update(
                "INSERT INTO conversation_preferences(conversation_id, user_id, muted, updated_at) VALUES (?,?,?, now()) " +
                        "ON CONFLICT (conversation_id, user_id) DO UPDATE SET muted = EXCLUDED.muted, updated_at = now()",
                conversationId, userId, muted
        );
        return rows > 0;
    }

    public Map<Long, Boolean> mutedByConversationIds(long userId, List<Long> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) return Map.of();
        List<Long> ids = conversationIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] params = new Object[ids.size() + 1];
        params[0] = userId;
        for (int i = 0; i < ids.size(); i++) params[i + 1] = ids.get(i);
        var rows = jdbc.query(
                "SELECT conversation_id, muted FROM conversation_preferences WHERE user_id = ? AND conversation_id IN (" + placeholders + ")",
                (rs, rowNum) -> java.util.Map.entry(rs.getLong("conversation_id"), rs.getBoolean("muted")),
                params
        );
        Map<Long, Boolean> out = new HashMap<>();
        for (var entry : rows) {
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    public boolean isMuted(long conversationId, long userId) {
        var rows = jdbc.query(
                "SELECT muted FROM conversation_preferences WHERE conversation_id = ? AND user_id = ?",
                (rs, rowNum) -> rs.getBoolean("muted"),
                conversationId, userId
        );
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }

    public Set<Long> mutedUserIdsForConversation(long conversationId) {
        return Set.copyOf(jdbc.query(
                "SELECT user_id FROM conversation_preferences WHERE conversation_id = ? AND muted = true",
                (rs, rowNum) -> rs.getLong("user_id"),
                conversationId
        ));
    }
}
