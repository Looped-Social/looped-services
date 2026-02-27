package com.looped.posts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class PostShareNudgeRepository {
    private final JdbcTemplate jdbc;

    public PostShareNudgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertState(long postId, long userId, OffsetDateTime eligibleAt, String variant) {
        jdbc.update(
                """
                INSERT INTO post_share_nudge_state(post_id, user_id, eligible_at, variant, created_at, updated_at)
                VALUES (?,?,?,?, now(), now())
                ON CONFLICT (post_id, user_id) DO NOTHING
                """,
                postId,
                userId,
                eligibleAt,
                variant
        );
    }

    public Optional<StateRow> find(long postId, long userId) {
        List<StateRow> rows = jdbc.query(
                """
                SELECT post_id, user_id, eligible_at, first_served_at, dismissed_at, share_tapped_at, variant
                FROM post_share_nudge_state
                WHERE post_id = ? AND user_id = ?
                """,
                MAPPER,
                postId,
                userId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean markServedIfEligible(long postId, long userId, OffsetDateTime now, String variant) {
        int rows = jdbc.update(
                """
                UPDATE post_share_nudge_state
                SET first_served_at = ?,
                    variant = COALESCE(variant, ?),
                    updated_at = now()
                WHERE post_id = ?
                  AND user_id = ?
                  AND first_served_at IS NULL
                  AND dismissed_at IS NULL
                  AND share_tapped_at IS NULL
                  AND eligible_at <= ?
                """,
                now,
                variant,
                postId,
                userId,
                now
        );
        return rows > 0;
    }

    public Optional<StateRow> markDismissed(long postId, long userId, OffsetDateTime now) {
        int rows = jdbc.update(
                """
                UPDATE post_share_nudge_state
                SET dismissed_at = COALESCE(dismissed_at, ?),
                    updated_at = now()
                WHERE post_id = ?
                  AND user_id = ?
                """,
                now,
                postId,
                userId
        );
        if (rows <= 0) return Optional.empty();
        return find(postId, userId);
    }

    public Optional<StateRow> markShareTapped(long postId, long userId, OffsetDateTime now) {
        int rows = jdbc.update(
                """
                UPDATE post_share_nudge_state
                SET share_tapped_at = COALESCE(share_tapped_at, ?),
                    updated_at = now()
                WHERE post_id = ?
                  AND user_id = ?
                """,
                now,
                postId,
                userId
        );
        if (rows <= 0) return Optional.empty();
        return find(postId, userId);
    }

    public ServeStats loadServeStats(long userId, OffsetDateTime dayStart, OffsetDateTime dayEnd) {
        return jdbc.query(
                """
                SELECT
                    COUNT(*) FILTER (WHERE first_served_at >= ? AND first_served_at < ?) AS served_today,
                    MAX(first_served_at) AS last_served_at
                FROM post_share_nudge_state
                WHERE user_id = ?
                  AND first_served_at IS NOT NULL
                """,
                rs -> {
                    if (!rs.next()) return new ServeStats(0, null);
                    return new ServeStats(
                            rs.getInt("served_today"),
                            rs.getObject("last_served_at", OffsetDateTime.class)
                    );
                },
                dayStart,
                dayEnd,
                userId
        );
    }

    public record StateRow(long postId,
                           long userId,
                           OffsetDateTime eligibleAt,
                           OffsetDateTime firstServedAt,
                           OffsetDateTime dismissedAt,
                           OffsetDateTime shareTappedAt,
                           String variant) {
    }

    public record ServeStats(int servedToday, OffsetDateTime lastServedAt) {
    }

    private static final RowMapper<StateRow> MAPPER = new RowMapper<>() {
        @Override
        public StateRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new StateRow(
                    rs.getLong("post_id"),
                    rs.getLong("user_id"),
                    rs.getObject("eligible_at", OffsetDateTime.class),
                    rs.getObject("first_served_at", OffsetDateTime.class),
                    rs.getObject("dismissed_at", OffsetDateTime.class),
                    rs.getObject("share_tapped_at", OffsetDateTime.class),
                    rs.getString("variant")
            );
        }
    };
}
