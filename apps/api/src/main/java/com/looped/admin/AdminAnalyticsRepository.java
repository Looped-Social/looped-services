package com.looped.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class AdminAnalyticsRepository {
    private final JdbcTemplate jdbc;

    public AdminAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean communityExists(long communityId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM communities WHERE id = ?)",
                Boolean.class,
                communityId
        );
        return Boolean.TRUE.equals(exists);
    }

    public List<NewUsersDailyRow> newUsersDaily(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        String sql = """
                WITH params AS (
                    SELECT ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts
                ),
                days AS (
                    SELECT d::date AS day
                    FROM params,
                         generate_series(
                             (from_ts AT TIME ZONE 'UTC')::date,
                             ((to_ts - interval '1 day') AT TIME ZONE 'UTC')::date,
                             interval '1 day'
                         ) d
                ),
                created AS (
                    SELECT (u.created_at AT TIME ZONE 'UTC')::date AS day, COUNT(*) AS created_count
                    FROM users u, params
                    WHERE u.created_at >= from_ts AND u.created_at < to_ts
                    GROUP BY 1
                ),
                deleted AS (
                    SELECT (u.deleted_at AT TIME ZONE 'UTC')::date AS day, COUNT(*) AS deleted_count
                    FROM users u, params
                    WHERE u.deleted_at IS NOT NULL
                      AND u.deleted_at >= from_ts AND u.deleted_at < to_ts
                    GROUP BY 1
                )
                SELECT d.day,
                       COALESCE(c.created_count, 0) AS created_users,
                       COALESCE(x.deleted_count, 0) AS deleted_users
                FROM days d
                LEFT JOIN created c ON c.day = d.day
                LEFT JOIN deleted x ON x.day = d.day
                ORDER BY d.day
                """;
        return jdbc.query(sql, (rs, rowNum) -> {
            NewUsersDailyRow row = new NewUsersDailyRow();
            row.day = rs.getObject("day", java.time.LocalDate.class);
            row.createdUsers = rs.getLong("created_users");
            row.deletedUsers = rs.getLong("deleted_users");
            return row;
        }, from, to);
    }

    public List<NewUsersWeeklyRow> newUsersWeekly(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        String sql = """
                WITH params AS (
                    SELECT ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts
                ),
                created AS (
                    SELECT (date_trunc('week', u.created_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')::date AS week_start,
                           COUNT(*) AS created_users
                    FROM users u, params
                    WHERE u.created_at >= from_ts AND u.created_at < to_ts
                    GROUP BY 1
                ),
                deleted AS (
                    SELECT (date_trunc('week', u.deleted_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')::date AS week_start,
                           COUNT(*) AS deleted_users
                    FROM users u, params
                    WHERE u.deleted_at IS NOT NULL
                      AND u.deleted_at >= from_ts AND u.deleted_at < to_ts
                    GROUP BY 1
                ),
                weeks AS (
                    SELECT w::date AS week_start
                    FROM params,
                         generate_series(
                             (date_trunc('week', from_ts AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')::date,
                             (date_trunc('week', (to_ts - interval '1 day') AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')::date,
                             interval '1 week'
                         ) w
                )
                SELECT w.week_start,
                       COALESCE(c.created_users, 0) AS created_users,
                       COALESCE(d.deleted_users, 0) AS deleted_users
                FROM weeks w
                LEFT JOIN created c ON c.week_start = w.week_start
                LEFT JOIN deleted d ON d.week_start = w.week_start
                ORDER BY w.week_start
                """;
        return jdbc.query(sql, (rs, rowNum) -> {
            NewUsersWeeklyRow row = new NewUsersWeeklyRow();
            row.weekStart = rs.getObject("week_start", java.time.LocalDate.class);
            row.createdUsers = rs.getLong("created_users");
            row.deletedUsers = rs.getLong("deleted_users");
            return row;
        }, from, to);
    }

    public List<ContentCreationDailyRow> contentCreationDaily(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        OffsetDateTime eventsFrom = from.minusDays(29);
        String sql = """
                WITH params AS (
                    SELECT ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts,
                           ?::timestamptz AS events_from_ts
                ),
                days AS (
                    SELECT d::date AS day
                    FROM params,
                         generate_series(
                             (from_ts AT TIME ZONE 'UTC')::date,
                             ((to_ts - interval '1 day') AT TIME ZONE 'UTC')::date,
                             interval '1 day'
                         ) d
                ),
                active_events AS (
                    SELECT p.author_id AS user_id, p.created_at AS ts
                    FROM posts p, params
                    WHERE p.author_id IS NOT NULL
                      AND p.removed_at IS NULL
                      AND p.created_at >= events_from_ts AND p.created_at < to_ts
                    UNION ALL
                    SELECT c.user_id AS user_id, c.created_at AS ts
                    FROM comments c, params
                    WHERE c.user_id IS NOT NULL
                      AND c.deleted_at IS NULL
                      AND c.created_at >= events_from_ts AND c.created_at < to_ts
                    UNION ALL
                    SELECT pr.user_id AS user_id, pl.created_at AS ts
                    FROM post_likes pl
                    JOIN principals pr ON pr.id = pl.liker_principal_id,
                         params
                    WHERE pr.user_id IS NOT NULL
                      AND pl.created_at >= events_from_ts AND pl.created_at < to_ts
                    UNION ALL
                    SELECT pr.user_id AS user_id, cl.created_at AS ts
                    FROM comment_likes cl
                    JOIN principals pr ON pr.id = cl.liker_principal_id,
                         params
                    WHERE pr.user_id IS NOT NULL
                      AND cl.created_at >= events_from_ts AND cl.created_at < to_ts
                    UNION ALL
                    SELECT pr.user_id AS user_id, ps.created_at AS ts
                    FROM post_shares ps
                    JOIN principals pr ON pr.id = ps.sharer_principal_id,
                         params
                    WHERE pr.user_id IS NOT NULL
                      AND ps.created_at >= events_from_ts AND ps.created_at < to_ts
                    UNION ALL
                    SELECT cf.user_id AS user_id, cf.created_at AS ts
                    FROM community_follows cf, params
                    WHERE cf.created_at >= events_from_ts AND cf.created_at < to_ts
                    UNION ALL
                    SELECT sj.user_id AS user_id, sj.created_at AS ts
                    FROM specialization_joins sj, params
                    WHERE sj.created_at >= events_from_ts AND sj.created_at < to_ts
                    UNION ALL
                    SELECT cv.user_id AS user_id, cv.verified_at AS ts
                    FROM community_verifications cv, params
                    WHERE cv.verified = true
                      AND cv.verified_at IS NOT NULL
                      AND cv.verified_at >= events_from_ts AND cv.verified_at < to_ts
                ),
                active_daily AS (
                    SELECT (ts AT TIME ZONE 'UTC')::date AS day, user_id
                    FROM active_events
                ),
                creators_daily AS (
                    SELECT (p.created_at AT TIME ZONE 'UTC')::date AS day, p.author_id AS user_id
                    FROM posts p, params
                    WHERE p.author_id IS NOT NULL
                      AND p.removed_at IS NULL
                      AND p.created_at >= from_ts AND p.created_at < to_ts
                    UNION
                    SELECT (c.created_at AT TIME ZONE 'UTC')::date AS day, c.user_id AS user_id
                    FROM comments c, params
                    WHERE c.user_id IS NOT NULL
                      AND c.deleted_at IS NULL
                      AND c.created_at >= from_ts AND c.created_at < to_ts
                )
                SELECT d.day,
                       (SELECT COUNT(DISTINCT user_id) FROM active_daily a WHERE a.day = d.day) AS active_users,
                       (SELECT COUNT(DISTINCT user_id) FROM creators_daily c WHERE c.day = d.day) AS creators
                FROM days d
                ORDER BY d.day
                """;
        return jdbc.query(sql, (rs, rowNum) -> {
            ContentCreationDailyRow row = new ContentCreationDailyRow();
            row.day = rs.getObject("day", java.time.LocalDate.class);
            row.activeUsers = rs.getLong("active_users");
            row.creators = rs.getLong("creators");
            return row;
        }, from, to, eventsFrom);
    }

    public List<PostsPerActiveCommunityDailyRow> postsPerActiveCommunityDaily(OffsetDateTime from, OffsetDateTime to, String kind) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        String sql = """
                WITH params AS (
                    SELECT ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts,
                           ?::text AS kind_filter
                ),
                days AS (
                    SELECT d::date AS day
                    FROM params,
                         generate_series(
                             (from_ts AT TIME ZONE 'UTC')::date,
                             ((to_ts - interval '1 day') AT TIME ZONE 'UTC')::date,
                             interval '1 day'
                         ) d
                ),
                posts_daily AS (
                    SELECT (p.created_at AT TIME ZONE 'UTC')::date AS day,
                           COUNT(*) AS posts_count,
                           COUNT(DISTINCT p.community_id) AS active_communities
                    FROM posts p
                    JOIN communities c ON c.id = p.community_id,
                         params
                    WHERE p.community_id IS NOT NULL
                      AND p.removed_at IS NULL
                      AND p.created_at >= from_ts AND p.created_at < to_ts
                      AND (kind_filter IS NULL OR c.kind = kind_filter)
                    GROUP BY 1
                )
                SELECT d.day,
                       COALESCE(pd.posts_count, 0) AS posts_count,
                       COALESCE(pd.active_communities, 0) AS active_communities
                FROM days d
                LEFT JOIN posts_daily pd ON pd.day = d.day
                ORDER BY d.day
                """;
        return jdbc.query(sql, (rs, rowNum) -> {
            PostsPerActiveCommunityDailyRow row = new PostsPerActiveCommunityDailyRow();
            row.day = rs.getObject("day", java.time.LocalDate.class);
            row.postsCount = rs.getLong("posts_count");
            row.activeCommunities = rs.getLong("active_communities");
            return row;
        }, from, to, kind);
    }

    public UniqueParticipantsSummaryRow uniqueParticipantsPerPost(long communityId, OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        String sql = """
                WITH params AS (
                    SELECT ?::bigint AS community_id_param,
                           ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts
                ),
                posts_in_range AS (
                    SELECT p.id, p.author_principal_id
                    FROM posts p, params
                    WHERE p.community_id = community_id_param
                      AND p.removed_at IS NULL
                      AND p.created_at >= from_ts AND p.created_at < to_ts
                ),
                participants AS (
                    SELECT pir.id AS post_id, pir.author_principal_id AS principal_id
                    FROM posts_in_range pir
                    UNION ALL
                    SELECT c.post_id AS post_id, c.author_principal_id AS principal_id
                    FROM comments c
                    JOIN posts_in_range pir ON pir.id = c.post_id
                    WHERE c.deleted_at IS NULL
                    UNION ALL
                    SELECT pl.post_id AS post_id, pl.liker_principal_id AS principal_id
                    FROM post_likes pl
                    JOIN posts_in_range pir ON pir.id = pl.post_id
                    UNION ALL
                    SELECT ps.post_id AS post_id, ps.sharer_principal_id AS principal_id
                    FROM post_shares ps
                    JOIN posts_in_range pir ON pir.id = ps.post_id
                ),
                counts AS (
                    SELECT post_id, COUNT(DISTINCT principal_id) AS participants
                    FROM participants
                    GROUP BY post_id
                )
                SELECT
                    (SELECT COUNT(*) FROM posts_in_range) AS posts_count,
                    COALESCE(AVG(participants), 0)::double precision AS avg_participants,
                    COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY participants), 0)::double precision AS p50_participants,
                    COALESCE(percentile_cont(0.9) WITHIN GROUP (ORDER BY participants), 0)::double precision AS p90_participants
                FROM counts
                """;
        return jdbc.queryForObject(sql, (rs, rowNum) -> {
            UniqueParticipantsSummaryRow row = new UniqueParticipantsSummaryRow();
            row.postsCount = rs.getLong("posts_count");
            row.avgParticipants = rs.getDouble("avg_participants");
            row.p50Participants = rs.getDouble("p50_participants");
            row.p90Participants = rs.getDouble("p90_participants");
            return row;
        }, communityId, from, to);
    }

    public RetentionByKindRow retentionByKind(OffsetDateTime from, OffsetDateTime to, String kind) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        OffsetDateTime actionsTo = to.plusDays(30);
        String sql = """
                WITH params AS (
                    SELECT ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts,
                           ?::timestamptz AS actions_to_ts,
                           ?::text AS kind_filter
                ),
                communities_filtered AS (
                    SELECT id, kind
                    FROM communities, params
                    WHERE kind_filter IS NULL OR kind = kind_filter
                ),
                cohorts AS (
                    SELECT sj.user_id,
                           sj.specialization_id AS community_id,
                           (sj.created_at AT TIME ZONE 'UTC')::date AS cohort_day
                    FROM specialization_joins sj
                    JOIN communities_filtered c ON c.id = sj.specialization_id,
                         params
                    WHERE c.kind = 'specialization'
                      AND sj.created_at >= from_ts AND sj.created_at < to_ts
                    UNION ALL
                    SELECT cv.user_id,
                           cv.community_id AS community_id,
                           (cv.verified_at AT TIME ZONE 'UTC')::date AS cohort_day
                    FROM community_verifications cv
                    JOIN communities_filtered c ON c.id = cv.community_id,
                         params
                    WHERE c.kind <> 'specialization'
                      AND cv.verified = true
                      AND cv.verified_at IS NOT NULL
                      AND cv.verified_at >= from_ts AND cv.verified_at < to_ts
                ),
                actions_raw AS (
                    SELECT p.author_id AS user_id, p.community_id, p.created_at AS ts
                    FROM posts p, params
                    WHERE p.community_id IS NOT NULL
                      AND p.author_id IS NOT NULL
                      AND p.removed_at IS NULL
                      AND p.created_at >= from_ts AND p.created_at < actions_to_ts
                    UNION ALL
                    SELECT pr.user_id AS user_id, p.community_id, pl.created_at AS ts
                    FROM post_likes pl
                    JOIN principals pr ON pr.id = pl.liker_principal_id
                    JOIN posts p ON p.id = pl.post_id,
                         params
                    WHERE p.community_id IS NOT NULL
                      AND pr.user_id IS NOT NULL
                      AND pl.created_at >= from_ts AND pl.created_at < actions_to_ts
                    UNION ALL
                    SELECT pr.user_id AS user_id, p.community_id, ps.created_at AS ts
                    FROM post_shares ps
                    JOIN principals pr ON pr.id = ps.sharer_principal_id
                    JOIN posts p ON p.id = ps.post_id,
                         params
                    WHERE p.community_id IS NOT NULL
                      AND pr.user_id IS NOT NULL
                      AND ps.created_at >= from_ts AND ps.created_at < actions_to_ts
                    UNION ALL
                    SELECT c.user_id AS user_id, p.community_id, c.created_at AS ts
                    FROM comments c
                    JOIN posts p ON p.id = c.post_id,
                         params
                    WHERE p.community_id IS NOT NULL
                      AND c.user_id IS NOT NULL
                      AND c.deleted_at IS NULL
                      AND c.created_at >= from_ts AND c.created_at < actions_to_ts
                ),
                actions AS (
                    SELECT DISTINCT user_id, community_id, (ts AT TIME ZONE 'UTC')::date AS action_day
                    FROM actions_raw
                    WHERE user_id IS NOT NULL AND community_id IS NOT NULL
                )
                SELECT
                    COUNT(DISTINCT (c.user_id, c.community_id, c.cohort_day)) AS cohort_size,
                    COUNT(DISTINCT CASE WHEN a.action_day = c.cohort_day + 1 THEN (c.user_id, c.community_id, c.cohort_day) END) AS retained_d1,
                    COUNT(DISTINCT CASE WHEN a.action_day = c.cohort_day + 7 THEN (c.user_id, c.community_id, c.cohort_day) END) AS retained_d7,
                    COUNT(DISTINCT CASE WHEN a.action_day = c.cohort_day + 30 THEN (c.user_id, c.community_id, c.cohort_day) END) AS retained_d30
                FROM cohorts c
                LEFT JOIN actions a
                    ON a.user_id = c.user_id
                   AND a.community_id = c.community_id
                   AND a.action_day IN (c.cohort_day + 1, c.cohort_day + 7, c.cohort_day + 30)
                """;
        return jdbc.queryForObject(sql, (rs, rowNum) -> {
            RetentionByKindRow row = new RetentionByKindRow();
            row.cohortSize = rs.getLong("cohort_size");
            row.retainedD1 = rs.getLong("retained_d1");
            row.retainedD7 = rs.getLong("retained_d7");
            row.retainedD30 = rs.getLong("retained_d30");
            return row;
        }, from, to, actionsTo, kind);
    }

    public TimeToFirstActionRow timeToFirstActions(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        String sql = """
                WITH params AS (
                    SELECT ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts
                ),
                cohort AS (
                    SELECT u.id, u.created_at
                    FROM users u, params
                    WHERE u.deleted_at IS NULL
                      AND u.created_at >= from_ts AND u.created_at < to_ts
                ),
                first_post AS (
                    SELECT p.author_id AS user_id, MIN(p.created_at) AS ts
                    FROM posts p
                    JOIN cohort c ON c.id = p.author_id
                    WHERE p.removed_at IS NULL
                    GROUP BY 1
                ),
                first_comment AS (
                    SELECT c.user_id AS user_id, MIN(c.created_at) AS ts
                    FROM comments c
                    JOIN cohort u ON u.id = c.user_id
                    WHERE c.deleted_at IS NULL
                    GROUP BY 1
                ),
                first_like AS (
                    SELECT pr.user_id AS user_id, MIN(pl.created_at) AS ts
                    FROM post_likes pl
                    JOIN principals pr ON pr.id = pl.liker_principal_id
                    JOIN cohort c ON c.id = pr.user_id
                    GROUP BY 1
                ),
                first_verify AS (
                    SELECT cv.user_id AS user_id, MIN(cv.verified_at) AS ts
                    FROM community_verifications cv
                    JOIN cohort c ON c.id = cv.user_id
                    WHERE cv.verified = true AND cv.verified_at IS NOT NULL
                    GROUP BY 1
                ),
                first_verify_global AS (
                    SELECT v.user_id AS user_id, MIN(v.verified_at) AS ts
                    FROM verifications v
                    JOIN cohort c ON c.id = v.user_id
                    WHERE v.verified = true AND v.verified_at IS NOT NULL
                    GROUP BY 1
                ),
                first_any_verify AS (
                    SELECT user_id, MIN(ts) AS ts
                    FROM (
                        SELECT * FROM first_verify
                        UNION ALL
                        SELECT * FROM first_verify_global
                    ) x
                    GROUP BY 1
                ),
                first_meaningful AS (
                    SELECT c.id AS user_id,
                           CASE
                               WHEN fp.ts IS NULL THEN fc.ts
                               WHEN fc.ts IS NULL THEN fp.ts
                               ELSE LEAST(fp.ts, fc.ts)
                           END AS ts
                    FROM cohort c
                    LEFT JOIN first_post fp ON fp.user_id = c.id
                    LEFT JOIN first_comment fc ON fc.user_id = c.id
                ),
                samples AS (
                    SELECT c.id AS user_id,
                           EXTRACT(EPOCH FROM (fm.ts - c.created_at)) AS to_meaningful_sec,
                           EXTRACT(EPOCH FROM (fp.ts - c.created_at)) AS to_post_sec,
                           EXTRACT(EPOCH FROM (fc.ts - c.created_at)) AS to_comment_sec,
                           EXTRACT(EPOCH FROM (fl.ts - c.created_at)) AS to_like_sec,
                           EXTRACT(EPOCH FROM (fav.ts - c.created_at)) AS to_verify_sec
                    FROM cohort c
                    LEFT JOIN first_meaningful fm ON fm.user_id = c.id
                    LEFT JOIN first_post fp ON fp.user_id = c.id
                    LEFT JOIN first_comment fc ON fc.user_id = c.id
                    LEFT JOIN first_like fl ON fl.user_id = c.id
                    LEFT JOIN first_any_verify fav ON fav.user_id = c.id
                )
                SELECT
                    (SELECT COUNT(*) FROM cohort) AS cohort_size,
                    COUNT(*) FILTER (WHERE to_meaningful_sec IS NOT NULL AND to_meaningful_sec >= 0) AS users_with_meaningful,
                    COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY to_meaningful_sec) FILTER (WHERE to_meaningful_sec IS NOT NULL AND to_meaningful_sec >= 0), 0) AS meaningful_p50_sec,
                    COALESCE(percentile_cont(0.9) WITHIN GROUP (ORDER BY to_meaningful_sec) FILTER (WHERE to_meaningful_sec IS NOT NULL AND to_meaningful_sec >= 0), 0) AS meaningful_p90_sec,
                    COUNT(*) FILTER (WHERE to_verify_sec IS NOT NULL AND to_verify_sec >= 0) AS users_with_verification,
                    COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY to_verify_sec) FILTER (WHERE to_verify_sec IS NOT NULL AND to_verify_sec >= 0), 0) AS verify_p50_sec,
                    COALESCE(percentile_cont(0.9) WITHIN GROUP (ORDER BY to_verify_sec) FILTER (WHERE to_verify_sec IS NOT NULL AND to_verify_sec >= 0), 0) AS verify_p90_sec
                FROM samples
                """;
        return jdbc.queryForObject(sql, (rs, rowNum) -> {
            TimeToFirstActionRow row = new TimeToFirstActionRow();
            row.cohortSize = rs.getLong("cohort_size");
            row.usersWithMeaningful = rs.getLong("users_with_meaningful");
            row.meaningfulP50Sec = rs.getDouble("meaningful_p50_sec");
            row.meaningfulP90Sec = rs.getDouble("meaningful_p90_sec");
            row.usersWithVerification = rs.getLong("users_with_verification");
            row.verifyP50Sec = rs.getDouble("verify_p50_sec");
            row.verifyP90Sec = rs.getDouble("verify_p90_sec");
            return row;
        }, from, to);
    }

    public TimeFromVerificationRow timeFromVerificationToFirstActions(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        String sql = """
                WITH params AS (
                    SELECT ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts
                ),
                verified_cohort AS (
                    SELECT user_id, MIN(ts) AS verified_at
                    FROM (
                        SELECT cv.user_id, cv.verified_at AS ts
                        FROM community_verifications cv, params
                        WHERE cv.verified = true AND cv.verified_at IS NOT NULL
                          AND cv.verified_at >= from_ts AND cv.verified_at < to_ts
                        UNION ALL
                        SELECT v.user_id, v.verified_at AS ts
                        FROM verifications v, params
                        WHERE v.verified = true AND v.verified_at IS NOT NULL
                          AND v.verified_at >= from_ts AND v.verified_at < to_ts
                    ) x
                    GROUP BY 1
                ),
                first_like AS (
                    SELECT user_id, MIN(ts) AS ts
                    FROM (
                        SELECT pr.user_id AS user_id, pl.created_at AS ts
                        FROM post_likes pl
                        JOIN principals pr ON pr.id = pl.liker_principal_id
                        JOIN verified_cohort vc ON vc.user_id = pr.user_id
                        WHERE pl.created_at >= vc.verified_at
                        UNION ALL
                        SELECT pr.user_id AS user_id, cl.created_at AS ts
                        FROM comment_likes cl
                        JOIN principals pr ON pr.id = cl.liker_principal_id
                        JOIN verified_cohort vc ON vc.user_id = pr.user_id
                        WHERE cl.created_at >= vc.verified_at
                    ) x
                    GROUP BY 1
                ),
                first_comment AS (
                    SELECT c.user_id AS user_id, MIN(c.created_at) AS ts
                    FROM comments c
                    JOIN verified_cohort vc ON vc.user_id = c.user_id
                    WHERE c.deleted_at IS NULL
                      AND c.user_id IS NOT NULL
                      AND c.created_at >= vc.verified_at
                    GROUP BY 1
                ),
                first_post AS (
                    SELECT p.author_id AS user_id, MIN(p.created_at) AS ts
                    FROM posts p
                    JOIN verified_cohort vc ON vc.user_id = p.author_id
                    WHERE p.removed_at IS NULL
                      AND p.author_id IS NOT NULL
                      AND p.created_at >= vc.verified_at
                    GROUP BY 1
                ),
                samples AS (
                    SELECT vc.user_id,
                           EXTRACT(EPOCH FROM (fl.ts - vc.verified_at)) AS to_like_sec,
                           EXTRACT(EPOCH FROM (fc.ts - vc.verified_at)) AS to_comment_sec,
                           EXTRACT(EPOCH FROM (fp.ts - vc.verified_at)) AS to_post_sec
                    FROM verified_cohort vc
                    LEFT JOIN first_like fl ON fl.user_id = vc.user_id
                    LEFT JOIN first_comment fc ON fc.user_id = vc.user_id
                    LEFT JOIN first_post fp ON fp.user_id = vc.user_id
                )
                SELECT
                    (SELECT COUNT(*) FROM verified_cohort) AS cohort_size,
                    COUNT(*) FILTER (WHERE to_like_sec IS NOT NULL AND to_like_sec >= 0) AS users_with_like,
                    COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY to_like_sec) FILTER (WHERE to_like_sec IS NOT NULL AND to_like_sec >= 0), 0) AS like_p50_sec,
                    COALESCE(percentile_cont(0.9) WITHIN GROUP (ORDER BY to_like_sec) FILTER (WHERE to_like_sec IS NOT NULL AND to_like_sec >= 0), 0) AS like_p90_sec,
                    COUNT(*) FILTER (WHERE to_comment_sec IS NOT NULL AND to_comment_sec >= 0) AS users_with_comment,
                    COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY to_comment_sec) FILTER (WHERE to_comment_sec IS NOT NULL AND to_comment_sec >= 0), 0) AS comment_p50_sec,
                    COALESCE(percentile_cont(0.9) WITHIN GROUP (ORDER BY to_comment_sec) FILTER (WHERE to_comment_sec IS NOT NULL AND to_comment_sec >= 0), 0) AS comment_p90_sec,
                    COUNT(*) FILTER (WHERE to_post_sec IS NOT NULL AND to_post_sec >= 0) AS users_with_post,
                    COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY to_post_sec) FILTER (WHERE to_post_sec IS NOT NULL AND to_post_sec >= 0), 0) AS post_p50_sec,
                    COALESCE(percentile_cont(0.9) WITHIN GROUP (ORDER BY to_post_sec) FILTER (WHERE to_post_sec IS NOT NULL AND to_post_sec >= 0), 0) AS post_p90_sec
                FROM samples
                """;
        return jdbc.queryForObject(sql, (rs, rowNum) -> {
            TimeFromVerificationRow row = new TimeFromVerificationRow();
            row.cohortSize = rs.getLong("cohort_size");
            row.usersWithLike = rs.getLong("users_with_like");
            row.likeP50Sec = rs.getDouble("like_p50_sec");
            row.likeP90Sec = rs.getDouble("like_p90_sec");
            row.usersWithComment = rs.getLong("users_with_comment");
            row.commentP50Sec = rs.getDouble("comment_p50_sec");
            row.commentP90Sec = rs.getDouble("comment_p90_sec");
            row.usersWithPost = rs.getLong("users_with_post");
            row.postP50Sec = rs.getDouble("post_p50_sec");
            row.postP90Sec = rs.getDouble("post_p90_sec");
            return row;
        }, from, to);
    }

    public RepeatOffendersRow repeatOffenders(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        String sql = """
                WITH params AS (
                    SELECT ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts
                ),
                violations AS (
                    SELECT b.user_id, b.created_at AS ts
                    FROM user_bans b, params
                    WHERE b.created_at >= from_ts AND b.created_at < to_ts
                    UNION ALL
                    SELECT p.author_id AS user_id, p.removed_at AS ts
                    FROM posts p, params
                    WHERE p.author_id IS NOT NULL
                      AND p.removed_at IS NOT NULL
                      AND (p.removed_reason IS NULL OR p.removed_reason <> 'user_deleted')
                      AND p.removed_at >= from_ts AND p.removed_at < to_ts
                ),
                per_user AS (
                    SELECT user_id, COUNT(*) AS violations_count
                    FROM violations
                    GROUP BY user_id
                )
                SELECT
                    (SELECT COUNT(*) FROM violations) AS violation_events,
                    (SELECT COUNT(*) FROM per_user) AS unique_violators,
                    (SELECT COUNT(*) FROM per_user WHERE violations_count >= 2) AS repeat_offenders
                """;
        return jdbc.queryForObject(sql, (rs, rowNum) -> {
            RepeatOffendersRow row = new RepeatOffendersRow();
            row.violationEvents = rs.getLong("violation_events");
            row.uniqueViolators = rs.getLong("unique_violators");
            row.repeatOffenders = rs.getLong("repeat_offenders");
            return row;
        }, from, to);
    }

    public NorthStarInteractionsRow northStarInteractions(OffsetDateTime from, OffsetDateTime to, Long communityId) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        String sql = """
                WITH params AS (
                    SELECT ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts,
                           ?::bigint AS community_id_param
                ),
                interactions AS (
                    SELECT pl.liker_principal_id AS actor_principal_id,
                           p.author_principal_id AS target_principal_id
                    FROM post_likes pl
                    JOIN posts p ON p.id = pl.post_id,
                         params
                    WHERE pl.created_at >= from_ts AND pl.created_at < to_ts
                      AND (community_id_param IS NULL OR p.community_id = community_id_param)
                      AND pl.liker_principal_id IS NOT NULL
                      AND p.author_principal_id IS NOT NULL
                      AND pl.liker_principal_id <> p.author_principal_id
                    UNION ALL
                    SELECT c.author_principal_id AS actor_principal_id,
                           p.author_principal_id AS target_principal_id
                    FROM comments c
                    JOIN posts p ON p.id = c.post_id,
                         params
                    WHERE c.deleted_at IS NULL
                      AND c.created_at >= from_ts AND c.created_at < to_ts
                      AND (community_id_param IS NULL OR p.community_id = community_id_param)
                      AND c.author_principal_id IS NOT NULL
                      AND p.author_principal_id IS NOT NULL
                      AND c.author_principal_id <> p.author_principal_id
                    UNION ALL
                    SELECT ps.sharer_principal_id AS actor_principal_id,
                           p.author_principal_id AS target_principal_id
                    FROM post_shares ps
                    JOIN posts p ON p.id = ps.post_id,
                         params
                    WHERE ps.created_at >= from_ts AND ps.created_at < to_ts
                      AND (community_id_param IS NULL OR p.community_id = community_id_param)
                      AND ps.sharer_principal_id IS NOT NULL
                      AND p.author_principal_id IS NOT NULL
                      AND ps.sharer_principal_id <> p.author_principal_id
                    UNION ALL
                    SELECT pf.follower_principal_id AS actor_principal_id,
                           pf.followee_principal_id AS target_principal_id
                    FROM principal_follows pf, params
                    WHERE pf.created_at >= from_ts AND pf.created_at < to_ts
                      AND pf.follower_principal_id <> pf.followee_principal_id
                ),
                user_user AS (
                    SELECT i.actor_principal_id, i.target_principal_id
                    FROM interactions i
                    JOIN principals a ON a.id = i.actor_principal_id
                    JOIN principals t ON t.id = i.target_principal_id
                    WHERE a.kind = 'user' AND t.kind = 'user'
                )
                SELECT
                    COUNT(*) AS interactions_total,
                    COUNT(DISTINCT actor_principal_id) AS unique_actors,
                    COUNT(DISTINCT target_principal_id) AS unique_targets,
                    COUNT(DISTINCT (actor_principal_id, target_principal_id)) AS unique_pairs
                FROM user_user
                """;
        return jdbc.queryForObject(sql, (rs, rowNum) -> {
            NorthStarInteractionsRow row = new NorthStarInteractionsRow();
            row.interactionsTotal = rs.getLong("interactions_total");
            row.uniqueActors = rs.getLong("unique_actors");
            row.uniqueTargets = rs.getLong("unique_targets");
            row.uniquePairs = rs.getLong("unique_pairs");
            return row;
        }, from, to, communityId);
    }

    public SupportTicketsRow supportTickets(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        String sql = """
                WITH params AS (
                    SELECT ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts
                )
                SELECT
                    (SELECT COUNT(*) FROM feedback f, params
                     WHERE f.created_at >= from_ts AND f.created_at < to_ts
                    ) AS feedback_count,
                    (SELECT COUNT(*) FROM users u WHERE u.deleted_at IS NULL) AS total_users
                """;
        return jdbc.queryForObject(sql, (rs, rowNum) -> {
            SupportTicketsRow row = new SupportTicketsRow();
            row.feedbackCount = rs.getLong("feedback_count");
            row.totalUsers = rs.getLong("total_users");
            return row;
        }, from, to);
    }

    public List<ActiveUsersDailyRow> activeUsersDaily(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        OffsetDateTime eventsFrom = from.minusDays(29);
        String sql = """
                WITH params AS (
                    SELECT ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts,
                           ?::timestamptz AS events_from_ts
                ),
                days AS (
                    SELECT d::date AS day
                    FROM params,
                         generate_series(
                             (from_ts AT TIME ZONE 'UTC')::date,
                             ((to_ts - interval '1 day') AT TIME ZONE 'UTC')::date,
                             interval '1 day'
                         ) d
                ),
                active_events AS (
                    SELECT p.author_id AS user_id, p.created_at AS ts
                    FROM posts p, params
                    WHERE p.author_id IS NOT NULL
                      AND p.removed_at IS NULL
                      AND p.created_at >= events_from_ts AND p.created_at < to_ts
                    UNION ALL
                    SELECT c.user_id AS user_id, c.created_at AS ts
                    FROM comments c, params
                    WHERE c.user_id IS NOT NULL
                      AND c.deleted_at IS NULL
                      AND c.created_at >= events_from_ts AND c.created_at < to_ts
                    UNION ALL
                    SELECT pr.user_id AS user_id, pl.created_at AS ts
                    FROM post_likes pl
                    JOIN principals pr ON pr.id = pl.liker_principal_id,
                         params
                    WHERE pr.user_id IS NOT NULL
                      AND pl.created_at >= events_from_ts AND pl.created_at < to_ts
                    UNION ALL
                    SELECT pr.user_id AS user_id, cl.created_at AS ts
                    FROM comment_likes cl
                    JOIN principals pr ON pr.id = cl.liker_principal_id,
                         params
                    WHERE pr.user_id IS NOT NULL
                      AND cl.created_at >= events_from_ts AND cl.created_at < to_ts
                    UNION ALL
                    SELECT pr.user_id AS user_id, ps.created_at AS ts
                    FROM post_shares ps
                    JOIN principals pr ON pr.id = ps.sharer_principal_id,
                         params
                    WHERE pr.user_id IS NOT NULL
                      AND ps.created_at >= events_from_ts AND ps.created_at < to_ts
                    UNION ALL
                    SELECT cf.user_id AS user_id, cf.created_at AS ts
                    FROM community_follows cf, params
                    WHERE cf.created_at >= events_from_ts AND cf.created_at < to_ts
                    UNION ALL
                    SELECT sj.user_id AS user_id, sj.created_at AS ts
                    FROM specialization_joins sj, params
                    WHERE sj.created_at >= events_from_ts AND sj.created_at < to_ts
                    UNION ALL
                    SELECT cv.user_id AS user_id, cv.verified_at AS ts
                    FROM community_verifications cv, params
                    WHERE cv.verified = true
                      AND cv.verified_at IS NOT NULL
                      AND cv.verified_at >= events_from_ts AND cv.verified_at < to_ts
                ),
                daily AS (
                    SELECT (ts AT TIME ZONE 'UTC')::date AS day, user_id
                    FROM active_events
                )
                SELECT d.day,
                       (SELECT COUNT(DISTINCT user_id) FROM daily x WHERE x.day = d.day) AS dau,
                       (SELECT COUNT(DISTINCT user_id) FROM daily x WHERE x.day BETWEEN d.day - 29 AND d.day) AS mau_30d
                FROM days d
                ORDER BY d.day
                """;
        return jdbc.query(sql, (rs, rowNum) -> {
            ActiveUsersDailyRow row = new ActiveUsersDailyRow();
            row.day = rs.getObject("day", java.time.LocalDate.class);
            row.dau = rs.getLong("dau");
            row.mau30d = rs.getLong("mau_30d");
            return row;
        }, from, to, eventsFrom);
    }

    public List<CommunityDailyMetricsRow> communityDailyMetrics(long communityId, OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        String sql = """
                WITH params AS (
                    SELECT ?::bigint AS community_id_param,
                           ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts
                ),
                days AS (
                    SELECT d::date AS day
                    FROM params,
                         generate_series(
                             (from_ts AT TIME ZONE 'UTC')::date,
                             ((to_ts - interval '1 day') AT TIME ZONE 'UTC')::date,
                             interval '1 day'
                         ) d
                ),
                posts_daily AS (
                    SELECT (p.created_at AT TIME ZONE 'UTC')::date AS day,
                           COUNT(*) AS posts_count,
                           COUNT(DISTINCT p.author_principal_id) AS unique_posters
                    FROM posts p, params
                    WHERE p.community_id = community_id_param
                      AND p.removed_at IS NULL
                      AND p.created_at >= from_ts AND p.created_at < to_ts
                    GROUP BY 1
                ),
                comments_daily AS (
                    SELECT (c.created_at AT TIME ZONE 'UTC')::date AS day,
                           COUNT(*) AS comments_count,
                           COUNT(DISTINCT c.author_principal_id) AS unique_commenters
                    FROM comments c
                    JOIN posts p ON p.id = c.post_id,
                         params
                    WHERE p.community_id = community_id_param
                      AND c.deleted_at IS NULL
                      AND c.created_at >= from_ts AND c.created_at < to_ts
                    GROUP BY 1
                ),
                post_likes_daily AS (
                    SELECT (pl.created_at AT TIME ZONE 'UTC')::date AS day,
                           COUNT(*) AS post_likes_count,
                           COUNT(DISTINCT pl.liker_principal_id) AS unique_post_likers
                    FROM post_likes pl
                    JOIN posts p ON p.id = pl.post_id,
                         params
                    WHERE p.community_id = community_id_param
                      AND pl.created_at >= from_ts AND pl.created_at < to_ts
                    GROUP BY 1
                ),
                post_shares_daily AS (
                    SELECT (ps.created_at AT TIME ZONE 'UTC')::date AS day,
                           COUNT(*) AS post_shares_count,
                           COUNT(DISTINCT ps.sharer_principal_id) AS unique_post_sharers
                    FROM post_shares ps
                    JOIN posts p ON p.id = ps.post_id,
                         params
                    WHERE p.community_id = community_id_param
                      AND ps.created_at >= from_ts AND ps.created_at < to_ts
                    GROUP BY 1
                )
                SELECT d.day,
                       COALESCE(pd.posts_count, 0) AS posts_count,
                       COALESCE(cd.comments_count, 0) AS comments_count,
                       COALESCE(ld.post_likes_count, 0) AS post_likes_count,
                       COALESCE(sd.post_shares_count, 0) AS post_shares_count,
                       COALESCE(pd.unique_posters, 0) AS unique_posters,
                       COALESCE(cd.unique_commenters, 0) AS unique_commenters,
                       COALESCE(ld.unique_post_likers, 0) AS unique_post_likers,
                       COALESCE(sd.unique_post_sharers, 0) AS unique_post_sharers
                FROM days d
                LEFT JOIN posts_daily pd ON pd.day = d.day
                LEFT JOIN comments_daily cd ON cd.day = d.day
                LEFT JOIN post_likes_daily ld ON ld.day = d.day
                LEFT JOIN post_shares_daily sd ON sd.day = d.day
                ORDER BY d.day
                """;
        return jdbc.query(sql, (rs, rowNum) -> {
            CommunityDailyMetricsRow row = new CommunityDailyMetricsRow();
            row.day = rs.getObject("day", java.time.LocalDate.class);
            row.postsCount = rs.getLong("posts_count");
            row.commentsCount = rs.getLong("comments_count");
            row.postLikesCount = rs.getLong("post_likes_count");
            row.postSharesCount = rs.getLong("post_shares_count");
            row.uniquePosters = rs.getLong("unique_posters");
            row.uniqueCommenters = rs.getLong("unique_commenters");
            row.uniquePostLikers = rs.getLong("unique_post_likers");
            row.uniquePostSharers = rs.getLong("unique_post_sharers");
            return row;
        }, communityId, from, to);
    }

    public List<CommunityRetentionDailyRow> communityRetentionDaily(long communityId, OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) throw new IllegalArgumentException("from/to required");
        OffsetDateTime actionsTo = to.plusDays(30);
        String sql = """
                WITH params AS (
                    SELECT ?::bigint AS community_id_param,
                           ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts,
                           ?::timestamptz AS actions_to_ts
                ),
                meta AS (
                    SELECT c.kind AS community_kind
                    FROM communities c, params
                    WHERE c.id = community_id_param
                ),
                cohort_days AS (
                    SELECT d::date AS cohort_day
                    FROM params,
                         generate_series(
                             (from_ts AT TIME ZONE 'UTC')::date,
                             ((to_ts - interval '1 day') AT TIME ZONE 'UTC')::date,
                             interval '1 day'
                         ) d
                ),
                cohorts AS (
                    SELECT sj.user_id,
                           (sj.created_at AT TIME ZONE 'UTC')::date AS cohort_day
                    FROM specialization_joins sj, params, meta
                    WHERE meta.community_kind = 'specialization'
                      AND sj.specialization_id = community_id_param
                      AND sj.created_at >= from_ts AND sj.created_at < to_ts
                    UNION ALL
                    SELECT cv.user_id,
                           (cv.verified_at AT TIME ZONE 'UTC')::date AS cohort_day
                    FROM community_verifications cv, params, meta
                    WHERE meta.community_kind <> 'specialization'
                      AND cv.community_id = community_id_param
                      AND cv.verified = true
                      AND cv.verified_at IS NOT NULL
                      AND cv.verified_at >= from_ts AND cv.verified_at < to_ts
                ),
                actions_raw AS (
                    SELECT p.author_id AS user_id, p.created_at AS ts
                    FROM posts p, params
                    WHERE p.community_id = community_id_param
                      AND p.author_id IS NOT NULL
                      AND p.removed_at IS NULL
                      AND p.created_at >= from_ts AND p.created_at < actions_to_ts
                    UNION ALL
                    SELECT pr.user_id AS user_id, pl.created_at AS ts
                    FROM post_likes pl
                    JOIN principals pr ON pr.id = pl.liker_principal_id
                    JOIN posts p ON p.id = pl.post_id,
                         params
                    WHERE p.community_id = community_id_param
                      AND pr.user_id IS NOT NULL
                      AND pl.created_at >= from_ts AND pl.created_at < actions_to_ts
                    UNION ALL
                    SELECT pr.user_id AS user_id, ps.created_at AS ts
                    FROM post_shares ps
                    JOIN principals pr ON pr.id = ps.sharer_principal_id
                    JOIN posts p ON p.id = ps.post_id,
                         params
                    WHERE p.community_id = community_id_param
                      AND pr.user_id IS NOT NULL
                      AND ps.created_at >= from_ts AND ps.created_at < actions_to_ts
                    UNION ALL
                    SELECT c.user_id AS user_id, c.created_at AS ts
                    FROM comments c
                    JOIN posts p ON p.id = c.post_id,
                         params
                    WHERE p.community_id = community_id_param
                      AND c.user_id IS NOT NULL
                      AND c.deleted_at IS NULL
                      AND c.created_at >= from_ts AND c.created_at < actions_to_ts
                ),
                actions AS (
                    SELECT DISTINCT user_id, (ts AT TIME ZONE 'UTC')::date AS action_day
                    FROM actions_raw
                    WHERE user_id IS NOT NULL
                )
                SELECT d.cohort_day,
                       COUNT(DISTINCT c.user_id) AS cohort_size,
                       COUNT(DISTINCT CASE WHEN a.action_day = d.cohort_day + 1 THEN c.user_id END) AS retained_d1,
                       COUNT(DISTINCT CASE WHEN a.action_day = d.cohort_day + 7 THEN c.user_id END) AS retained_d7,
                       COUNT(DISTINCT CASE WHEN a.action_day = d.cohort_day + 30 THEN c.user_id END) AS retained_d30
                FROM cohort_days d
                LEFT JOIN cohorts c ON c.cohort_day = d.cohort_day
                LEFT JOIN actions a ON a.user_id = c.user_id
                                AND a.action_day IN (d.cohort_day + 1, d.cohort_day + 7, d.cohort_day + 30)
                GROUP BY d.cohort_day
                ORDER BY d.cohort_day
                """;
        return jdbc.query(sql, (rs, rowNum) -> {
            CommunityRetentionDailyRow row = new CommunityRetentionDailyRow();
            row.cohortDay = rs.getObject("cohort_day", java.time.LocalDate.class);
            row.cohortSize = rs.getLong("cohort_size");
            row.retainedD1 = rs.getLong("retained_d1");
            row.retainedD7 = rs.getLong("retained_d7");
            row.retainedD30 = rs.getLong("retained_d30");
            return row;
        }, communityId, from, to, actionsTo);
    }

    public TrustSafetySummaryRow trustSafetySummary(OffsetDateTime from, OffsetDateTime to) {
        String sql = """
                WITH params AS (
                    SELECT ?::timestamptz AS from_ts,
                           ?::timestamptz AS to_ts
                ),
                user_counts AS (
                    SELECT
                        (SELECT COUNT(*) FROM users u WHERE u.deleted_at IS NULL) AS total_users,
                        (SELECT COUNT(*) FROM verifications v WHERE v.verified = true) AS verified_users_global,
                        (SELECT COUNT(DISTINCT cv.user_id)
                         FROM community_verifications cv
                         WHERE cv.verified = true
                           AND (cv.expires_at IS NULL OR cv.expires_at > now())
                        ) AS verified_users_any_community
                ),
                posts_stats AS (
                    SELECT
                        COUNT(*) AS posts_total,
                        COUNT(*) FILTER (WHERE p.is_anon = true) AS posts_anon
                    FROM posts p, params
                    WHERE p.removed_at IS NULL
                      AND p.created_at >= from_ts AND p.created_at < to_ts
                ),
                comments_stats AS (
                    SELECT
                        COUNT(*) AS comments_total,
                        COUNT(*) FILTER (WHERE c.user_id IS NULL) AS comments_anon
                    FROM comments c, params
                    WHERE c.deleted_at IS NULL
                      AND c.created_at >= from_ts AND c.created_at < to_ts
                ),
                post_likes_stats AS (
                    SELECT
                        COUNT(*) AS likes_total,
                        COUNT(*) FILTER (WHERE pr.kind = 'anon') AS likes_anon
                    FROM post_likes pl
                    JOIN principals pr ON pr.id = pl.liker_principal_id,
                         params
                    WHERE pl.created_at >= from_ts AND pl.created_at < to_ts
                ),
                appeal_stats AS (
                    SELECT
                        COUNT(*) FILTER (WHERE a.reviewed_at IS NOT NULL) AS appeals_reviewed,
                        COUNT(*) FILTER (WHERE a.status = 'approved') AS appeals_approved
                    FROM appeals a, params
                    WHERE a.created_at >= from_ts AND a.created_at < to_ts
                )
                SELECT
                    uc.total_users,
                    uc.verified_users_global,
                    uc.verified_users_any_community,
                    ps.posts_total,
                    ps.posts_anon,
                    cs.comments_total,
                    cs.comments_anon,
                    ls.likes_total,
                    ls.likes_anon,
                    aps.appeals_reviewed,
                    aps.appeals_approved
                FROM user_counts uc
                CROSS JOIN posts_stats ps
                CROSS JOIN comments_stats cs
                CROSS JOIN post_likes_stats ls
                CROSS JOIN appeal_stats aps
                """;
        return jdbc.queryForObject(sql, (rs, rowNum) -> {
            TrustSafetySummaryRow row = new TrustSafetySummaryRow();
            row.totalUsers = rs.getLong("total_users");
            row.verifiedUsersGlobal = rs.getLong("verified_users_global");
            row.verifiedUsersAnyCommunity = rs.getLong("verified_users_any_community");
            row.postsTotal = rs.getLong("posts_total");
            row.postsAnon = rs.getLong("posts_anon");
            row.commentsTotal = rs.getLong("comments_total");
            row.commentsAnon = rs.getLong("comments_anon");
            row.likesTotal = rs.getLong("likes_total");
            row.likesAnon = rs.getLong("likes_anon");
            row.appealsReviewed = rs.getLong("appeals_reviewed");
            row.appealsApproved = rs.getLong("appeals_approved");
            return row;
        }, from, to);
    }

    public List<CommunityMetricRow> communityLeaderboard(Long communityId, OffsetDateTime from, OffsetDateTime to,
                                                          String orderByExpr, int limit) {
        Subquery likes = buildLikesSubquery(from, to);
        Subquery shares = buildSharesSubquery(from, to);
        Subquery followers = buildFollowersSubquery(from, to);
        Subquery verifications = buildVerificationsSubquery(from, to);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM (");
        sql.append("SELECT c.id, c.kind, c.name, c.image_url, ");
        sql.append("(").append(likes.sql()).append(") AS likes_count, ");
        sql.append("(").append(shares.sql()).append(") AS shares_count, ");
        sql.append("(").append(followers.sql()).append(") AS followers_count, ");
        sql.append("(").append(verifications.sql()).append(") AS verifications_count ");
        sql.append("FROM communities c ");
        if (communityId != null) {
            sql.append("WHERE c.id = ? ");
        }
        sql.append(") t ");
        sql.append("ORDER BY ").append(orderByExpr).append(" DESC, t.id DESC ");
        sql.append("LIMIT ? ");

        List<Object> params = new ArrayList<>();
        params.addAll(likes.params());
        params.addAll(shares.params());
        params.addAll(followers.params());
        params.addAll(verifications.params());
        if (communityId != null) params.add(communityId);
        params.add(limit);

        return jdbc.query(sql.toString(), params.toArray(), COMMUNITY_MAPPER);
    }

    public List<HashtagMetricRow> hashtagsLeaderboard(Long communityId, OffsetDateTime from, OffsetDateTime to, int limit) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT h.id, h.name, COUNT(hp.post_id) AS usage_count ");
        sql.append("FROM hashtags h ");
        sql.append("JOIN hashtag_posts hp ON hp.hashtag_id = h.id ");
        sql.append("JOIN posts p ON p.id = hp.post_id AND p.removed_at IS NULL ");
        sql.append("WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (communityId != null) {
            sql.append("AND p.community_id = ? ");
            params.add(communityId);
        }
        if (from != null) {
            sql.append("AND hp.created_at >= ? ");
            params.add(from);
        }
        if (to != null) {
            sql.append("AND hp.created_at < ? ");
            params.add(to);
        }
        sql.append("GROUP BY h.id, h.name ");
        sql.append("ORDER BY usage_count DESC, h.id DESC ");
        sql.append("LIMIT ? ");
        params.add(limit);

        return jdbc.query(sql.toString(), params.toArray(), HASHTAG_MAPPER);
    }

    public UserStatsRow userStats(OffsetDateTime from, OffsetDateTime to) {
        long totalUsers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE deleted_at IS NULL",
                Long.class
        );
        long deletedUsers = jdbc.queryForObject(
                buildUserCountQuery("deleted_at IS NOT NULL", "deleted_at", from, to),
                buildUserCountParams(from, to),
                Long.class
        );
        long newUsers = jdbc.queryForObject(
                buildUserCountQuery("deleted_at IS NULL", "created_at", from, to),
                buildUserCountParams(from, to),
                Long.class
        );
        UserStatsRow row = new UserStatsRow();
        row.totalUsers = totalUsers;
        row.newUsers = newUsers;
        row.deletedUsers = deletedUsers;
        return row;
    }

    private Object[] buildUserCountParams(OffsetDateTime from, OffsetDateTime to) {
        List<Object> params = new ArrayList<>();
        if (from != null) params.add(from);
        if (to != null) params.add(to);
        return params.toArray();
    }

    private String buildUserCountQuery(String baseFilter, String timeColumn, OffsetDateTime from, OffsetDateTime to) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM users WHERE ");
        sql.append(baseFilter).append(" ");
        if (from != null) sql.append("AND ").append(timeColumn).append(" >= ? ");
        if (to != null) sql.append("AND ").append(timeColumn).append(" < ? ");
        return sql.toString();
    }

    private Subquery buildLikesSubquery(OffsetDateTime from, OffsetDateTime to) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM posts p ");
        sql.append("JOIN post_likes pl ON pl.post_id = p.id ");
        sql.append("WHERE p.community_id = c.id AND p.removed_at IS NULL ");
        List<Object> params = new ArrayList<>();
        if (from != null) {
            sql.append("AND pl.created_at >= ? ");
            params.add(from);
        }
        if (to != null) {
            sql.append("AND pl.created_at < ? ");
            params.add(to);
        }
        return new Subquery(sql.toString(), params);
    }

    private Subquery buildSharesSubquery(OffsetDateTime from, OffsetDateTime to) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM posts p ");
        sql.append("JOIN post_shares ps ON ps.post_id = p.id ");
        sql.append("WHERE p.community_id = c.id AND p.removed_at IS NULL ");
        List<Object> params = new ArrayList<>();
        if (from != null) {
            sql.append("AND ps.created_at >= ? ");
            params.add(from);
        }
        if (to != null) {
            sql.append("AND ps.created_at < ? ");
            params.add(to);
        }
        return new Subquery(sql.toString(), params);
    }

    private Subquery buildFollowersSubquery(OffsetDateTime from, OffsetDateTime to) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM community_follows cf ");
        sql.append("WHERE cf.community_id = c.id ");
        List<Object> params = new ArrayList<>();
        if (from != null) {
            sql.append("AND cf.created_at >= ? ");
            params.add(from);
        }
        if (to != null) {
            sql.append("AND cf.created_at < ? ");
            params.add(to);
        }
        return new Subquery(sql.toString(), params);
    }

    private Subquery buildVerificationsSubquery(OffsetDateTime from, OffsetDateTime to) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM community_verifications cv ");
        sql.append("WHERE cv.community_id = c.id AND cv.verified = true ");
        List<Object> params = new ArrayList<>();
        if (from != null) {
            sql.append("AND cv.verified_at >= ? ");
            params.add(from);
        }
        if (to != null) {
            sql.append("AND cv.verified_at < ? ");
            params.add(to);
        }
        if (from == null && to == null) {
            sql.append("AND (cv.expires_at IS NULL OR cv.expires_at > now()) ");
        }
        return new Subquery(sql.toString(), params);
    }

    private static final RowMapper<CommunityMetricRow> COMMUNITY_MAPPER = new RowMapper<>() {
        @Override
        public CommunityMetricRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            CommunityMetricRow row = new CommunityMetricRow();
            row.id = rs.getLong("id");
            row.kind = rs.getString("kind");
            row.name = rs.getString("name");
            row.imageUrl = rs.getString("image_url");
            row.likesCount = rs.getLong("likes_count");
            row.sharesCount = rs.getLong("shares_count");
            row.followersCount = rs.getLong("followers_count");
            row.verificationsCount = rs.getLong("verifications_count");
            return row;
        }
    };

    private static final RowMapper<HashtagMetricRow> HASHTAG_MAPPER = new RowMapper<>() {
        @Override
        public HashtagMetricRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            HashtagMetricRow row = new HashtagMetricRow();
            row.id = rs.getLong("id");
            row.name = rs.getString("name");
            row.usageCount = rs.getLong("usage_count");
            return row;
        }
    };

    private record Subquery(String sql, List<Object> params) {}

    public static class CommunityMetricRow {
        public long id;
        public String kind;
        public String name;
        public String imageUrl;
        public long likesCount;
        public long sharesCount;
        public long followersCount;
        public long verificationsCount;
    }

    public static class HashtagMetricRow {
        public long id;
        public String name;
        public long usageCount;
    }

    public static class UserStatsRow {
        public long totalUsers;
        public long newUsers;
        public long deletedUsers;
    }

    public static class ActiveUsersDailyRow {
        public java.time.LocalDate day;
        public long dau;
        public long mau30d;
    }

    public static class CommunityDailyMetricsRow {
        public java.time.LocalDate day;
        public long postsCount;
        public long commentsCount;
        public long postLikesCount;
        public long postSharesCount;
        public long uniquePosters;
        public long uniqueCommenters;
        public long uniquePostLikers;
        public long uniquePostSharers;
    }

    public static class CommunityRetentionDailyRow {
        public java.time.LocalDate cohortDay;
        public long cohortSize;
        public long retainedD1;
        public long retainedD7;
        public long retainedD30;
    }

    public static class TrustSafetySummaryRow {
        public long totalUsers;
        public long verifiedUsersGlobal;
        public long verifiedUsersAnyCommunity;
        public long postsTotal;
        public long postsAnon;
        public long commentsTotal;
        public long commentsAnon;
        public long likesTotal;
        public long likesAnon;
        public long appealsReviewed;
        public long appealsApproved;
    }

    public static class NewUsersDailyRow {
        public java.time.LocalDate day;
        public long createdUsers;
        public long deletedUsers;
    }

    public static class NewUsersWeeklyRow {
        public java.time.LocalDate weekStart;
        public long createdUsers;
        public long deletedUsers;
    }

    public static class ContentCreationDailyRow {
        public java.time.LocalDate day;
        public long activeUsers;
        public long creators;
    }

    public static class PostsPerActiveCommunityDailyRow {
        public java.time.LocalDate day;
        public long postsCount;
        public long activeCommunities;
    }

    public static class UniqueParticipantsSummaryRow {
        public long postsCount;
        public double avgParticipants;
        public double p50Participants;
        public double p90Participants;
    }

    public static class RetentionByKindRow {
        public long cohortSize;
        public long retainedD1;
        public long retainedD7;
        public long retainedD30;
    }

    public static class TimeToFirstActionRow {
        public long cohortSize;
        public long usersWithMeaningful;
        public double meaningfulP50Sec;
        public double meaningfulP90Sec;
        public long usersWithVerification;
        public double verifyP50Sec;
        public double verifyP90Sec;
    }

    public static class TimeFromVerificationRow {
        public long cohortSize;
        public long usersWithLike;
        public double likeP50Sec;
        public double likeP90Sec;
        public long usersWithComment;
        public double commentP50Sec;
        public double commentP90Sec;
        public long usersWithPost;
        public double postP50Sec;
        public double postP90Sec;
    }

    public static class RepeatOffendersRow {
        public long violationEvents;
        public long uniqueViolators;
        public long repeatOffenders;
    }

    public static class NorthStarInteractionsRow {
        public long interactionsTotal;
        public long uniqueActors;
        public long uniqueTargets;
        public long uniquePairs;
    }

    public static class SupportTicketsRow {
        public long feedbackCount;
        public long totalUsers;
    }
}
