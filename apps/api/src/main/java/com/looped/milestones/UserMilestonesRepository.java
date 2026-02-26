package com.looped.milestones;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public class UserMilestonesRepository {
    private final JdbcTemplate jdbc;

    public UserMilestonesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean awardIfAbsent(long userId, String milestoneType, Long postId, Map<String, Object> metadata) {
        if (userId <= 0) return false;
        if (milestoneType == null || milestoneType.isBlank()) return false;
        String type = milestoneType.trim().toLowerCase(java.util.Locale.ROOT);
        // Concurrency-safe: only one insert wins; callers treat "inserted" as newly-awarded.
        Boolean inserted = jdbc.query(
                "INSERT INTO user_milestones(user_id, milestone_type, post_id, metadata) " +
                        "VALUES (?,?,?, COALESCE(?::jsonb, '{}'::jsonb)) " +
                        "ON CONFLICT (user_id, milestone_type) DO NOTHING " +
                        "RETURNING 1",
                (ResultSetExtractor<Boolean>) rs -> rs.next(),
                userId,
                type,
                postId,
                metadata == null || metadata.isEmpty() ? null : toJson(metadata)
        );
        return Boolean.TRUE.equals(inserted);
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata);
        } catch (Exception ignored) {
            return null;
        }
    }
}
