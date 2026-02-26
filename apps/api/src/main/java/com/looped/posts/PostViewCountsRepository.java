package com.looped.posts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class PostViewCountsRepository {
    private final JdbcTemplate jdbc;

    public PostViewCountsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, Long> uniquePostOpenViewersByPostIds(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return Map.of();

        String placeholders = String.join(",", Collections.nCopies(postIds.size(), "?"));
        String sql = "SELECT p.id AS post_id, " +
                "COALESCE(COUNT(DISTINCT te.user_id) FILTER (WHERE te.user_id <> p.author_id), 0) AS unique_view_count " +
                "FROM posts p " +
                "LEFT JOIN telemetry_events te " +
                "  ON te.post_id = p.id " +
                " AND te.type = 'post_open' " +
                "WHERE p.id IN (" + placeholders + ") " +
                "GROUP BY p.id";

        return jdbc.query(sql, rs -> {
            Map<Long, Long> out = new HashMap<>();
            while (rs.next()) {
                out.put(rs.getLong("post_id"), rs.getLong("unique_view_count"));
            }
            return out;
        }, postIds.toArray());
    }
}
