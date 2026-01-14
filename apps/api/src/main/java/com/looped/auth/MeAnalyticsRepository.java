package com.looped.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public class MeAnalyticsRepository {
    private final JdbcTemplate jdbc;

    public MeAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long totalHeartsReceived(long userId) {
        Long val = jdbc.queryForObject(
                "SELECT COALESCE(SUM(likes_count), 0) FROM posts WHERE author_id = ? AND removed_at IS NULL",
                Long.class, userId
        );
        return val == null ? 0 : val;
    }

    public long heartsReceivedSince(long userId, OffsetDateTime since) {
        Long val = jdbc.queryForObject(
                "SELECT COUNT(*) FROM post_likes l " +
                        "JOIN posts p ON p.id = l.post_id " +
                        "WHERE p.author_id = ? AND p.removed_at IS NULL AND l.created_at >= ?",
                Long.class, userId, since
        );
        return val == null ? 0 : val;
    }

    public long postsCreatedSince(long userId, OffsetDateTime since) {
        Long val = jdbc.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE author_id = ? AND removed_at IS NULL AND created_at >= ?",
                Long.class, userId, since
        );
        return val == null ? 0 : val;
    }
}

