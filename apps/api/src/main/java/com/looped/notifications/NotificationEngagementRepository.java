package com.looped.notifications;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class NotificationEngagementRepository {
    private final JdbcTemplate jdbc;

    public NotificationEngagementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SinceAwayCandidate> listSinceAwayCandidates(int awayHours, int minNewPosts, long cursorUserId, int limit) {
        int hours = Math.max(1, Math.min(awayHours, 24 * 30));
        int minPosts = Math.max(1, minNewPosts);
        int lim = Math.max(1, Math.min(limit, 5_000));
        return jdbc.query(
                """
                SELECT
                    u.id AS user_id,
                    u.last_app_open_at,
                    COUNT(p.id)::int AS new_posts_count
                FROM users u
                JOIN posts p
                    ON p.company_id = u.company_id
                    AND p.created_at > u.last_app_open_at
                    AND p.removed_at IS NULL
                    AND p.visibility = 'public'
                WHERE u.id > ?
                AND u.deleted_at IS NULL
                AND u.disabled_at IS NULL
                AND u.company_id IS NOT NULL
                AND u.onboarding_completed_at IS NOT NULL
                AND u.last_app_open_at IS NOT NULL
                AND u.last_app_open_at <= now() - (? || ' hours')::interval
                GROUP BY u.id, u.last_app_open_at
                HAVING COUNT(p.id) >= ?
                ORDER BY u.id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new SinceAwayCandidate(
                        rs.getLong("user_id"),
                        rs.getObject("last_app_open_at", OffsetDateTime.class),
                        rs.getInt("new_posts_count")
                ),
                cursorUserId,
                Integer.toString(hours),
                minPosts,
                lim
        );
    }

    public List<TrendingCandidate> listTrendingCandidates(int awayHours, long cursorUserId, int limit) {
        int hours = Math.max(1, Math.min(awayHours, 24 * 30));
        int lim = Math.max(1, Math.min(limit, 5_000));
        return jdbc.query(
                """
                SELECT id, firebase_uid, display_community_id
                FROM users
                WHERE id > ?
                AND deleted_at IS NULL
                AND disabled_at IS NULL
                AND company_id IS NOT NULL
                AND onboarding_completed_at IS NOT NULL
                AND last_app_open_at IS NOT NULL
                AND last_app_open_at <= now() - (? || ' hours')::interval
                ORDER BY id ASC
                LIMIT ?
                """,
                (rs, rowNum) -> {
                    long displayCommunityId = rs.getLong("display_community_id");
                    return new TrendingCandidate(
                            rs.getLong("id"),
                            rs.getString("firebase_uid"),
                            rs.wasNull() ? null : displayCommunityId
                    );
                },
                cursorUserId,
                Integer.toString(hours),
                lim
        );
    }

    public record SinceAwayCandidate(long userId,
                                     OffsetDateTime lastAppOpenAt,
                                     int newPostsCount) {}

    public record TrendingCandidate(long userId,
                                    String firebaseUid,
                                    Long displayCommunityId) {}
}
