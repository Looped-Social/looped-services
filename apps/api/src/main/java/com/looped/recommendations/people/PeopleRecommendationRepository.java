package com.looped.recommendations.people;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
class PeopleRecommendationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    PeopleRecommendationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    List<CandidateRow> findCandidates(PeopleRecommendationTypes.Rail rail,
                                      ViewerContext ctx,
                                      CursorKeyset cursor,
                                      int limit,
                                      int activeWindowDays,
                                      int openReportExclusionThreshold,
                                      int maxViewerExposurePerCandidate24h) {
        String railCondition = switch (rail) {
            case PYMK -> "(COALESCE(mutual.mutual_count, 0) > 0 OR COALESCE(shared_communities.shared_count, 0) > 0 OR COALESCE(shared_specs.shared_count, 0) > 0 OR cfm.candidate_principal_id IS NOT NULL)";
            case COMMUNITY -> "(me.target_community_id IS NOT NULL AND tcm.user_id IS NOT NULL)";
            case ACTIVE_COMMUNITY -> "(me.target_community_id IS NOT NULL AND COALESCE(activity.target_recent_posts, 0) > 0)";
        };

        String railBoostExpr = switch (rail) {
            case PYMK -> "(COALESCE(mutual_count, 0) * 50000 + CASE WHEN follows_viewer THEN 30000 ELSE 0 END)";
            case COMMUNITY -> "(CASE WHEN in_target_community THEN 250000 ELSE 0 END + COALESCE(target_recent_posts, 0) * 15000)";
            case ACTIVE_COMMUNITY -> "(COALESCE(target_recent_posts, 0) * 90000 + CASE WHEN in_target_community THEN 100000 ELSE 0 END)";
        };

        String sqlTemplate = """
                WITH me AS (
                    SELECT ?::bigint AS viewer_user_id,
                           ?::bigint AS viewer_principal_id,
                           ?::bigint AS company_id,
                           ?::bigint AS target_community_id,
                           ?::timestamptz AS as_of
                ),
                viewer_following AS (
                    SELECT f.followee_principal_id
                    FROM principal_follows f
                    JOIN me ON me.viewer_principal_id = f.follower_principal_id
                ),
                mutual AS (
                    SELECT pf2.follower_principal_id AS candidate_principal_id,
                           COUNT(*)::int AS mutual_count
                    FROM principal_follows pf1
                    JOIN principal_follows pf2
                      ON pf1.followee_principal_id = pf2.followee_principal_id
                    JOIN me ON me.viewer_principal_id = pf1.follower_principal_id
                    WHERE pf2.follower_principal_id <> me.viewer_principal_id
                    GROUP BY pf2.follower_principal_id
                ),
                candidate_follows_me AS (
                    SELECT f.follower_principal_id AS candidate_principal_id
                    FROM principal_follows f
                    JOIN me ON me.viewer_principal_id = f.followee_principal_id
                ),
                viewer_verified_communities AS (
                    SELECT cv.community_id
                    FROM community_verifications cv
                    JOIN me ON cv.user_id = me.viewer_user_id
                    WHERE cv.verified = true
                      AND (cv.expires_at IS NULL OR cv.expires_at > me.as_of)
                ),
                shared_communities AS (
                    SELECT cv.user_id,
                           COUNT(*)::int AS shared_count
                    FROM community_verifications cv
                    JOIN viewer_verified_communities vvc ON vvc.community_id = cv.community_id
                    JOIN me ON true
                    WHERE cv.verified = true
                      AND (cv.expires_at IS NULL OR cv.expires_at > me.as_of)
                      AND cv.user_id <> me.viewer_user_id
                    GROUP BY cv.user_id
                ),
                target_community_members AS (
                    SELECT cv.user_id
                    FROM community_verifications cv
                    JOIN me ON me.target_community_id IS NOT NULL AND cv.community_id = me.target_community_id
                    WHERE cv.verified = true
                      AND (cv.expires_at IS NULL OR cv.expires_at > me.as_of)
                ),
                viewer_specs AS (
                    SELECT sj.specialization_id
                    FROM specialization_joins sj
                    JOIN me ON sj.user_id = me.viewer_user_id
                ),
                shared_specs AS (
                    SELECT sj.user_id,
                           COUNT(*)::int AS shared_count
                    FROM specialization_joins sj
                    JOIN viewer_specs vs ON vs.specialization_id = sj.specialization_id
                    JOIN me ON true
                    WHERE sj.user_id <> me.viewer_user_id
                    GROUP BY sj.user_id
                ),
                activity AS (
                    SELECT p.author_id AS user_id,
                           COUNT(*) FILTER (
                               WHERE p.created_at >= me.as_of - (? * interval '1 day')
                           )::int AS recent_posts,
                           COUNT(*) FILTER (
                               WHERE me.target_community_id IS NOT NULL
                                 AND p.community_id = me.target_community_id
                                 AND p.created_at >= me.as_of - (? * interval '1 day')
                           )::int AS target_recent_posts
                    FROM posts p
                    CROSS JOIN me
                    WHERE p.author_id IS NOT NULL
                      AND p.created_at <= me.as_of
                    GROUP BY p.author_id
                ),
                viewer_hidden AS (
                    SELECT s.candidate_user_id
                    FROM people_reco_suppressions s
                    JOIN me ON s.viewer_user_id = me.viewer_user_id
                    WHERE s.suppression_type IN ('hide', 'less_like_this')
                      AND s.expires_at > me.as_of
                ),
                viewer_reported AS (
                    SELECT DISTINCT r.target_id AS user_id
                    FROM reports r
                    JOIN me ON r.reporter_id = me.viewer_user_id
                    WHERE r.target_type = 'user'
                      AND r.status <> 'dismissed'
                ),
                open_reports AS (
                    SELECT r.target_id AS user_id,
                           COUNT(DISTINCT r.reporter_id)::int AS open_reporters
                    FROM reports r
                    WHERE r.target_type = 'user'
                      AND r.status = 'open'
                    GROUP BY r.target_id
                ),
                viewer_exposure AS (
                    SELECT a.candidate_user_id AS user_id,
                           COUNT(*)::int AS viewer_exposure_count
                    FROM people_reco_served_audit a
                    JOIN me ON a.viewer_user_id = me.viewer_user_id
                    WHERE a.created_at >= me.as_of - interval '24 hours'
                    GROUP BY a.candidate_user_id
                ),
                base AS (
                    SELECT u.id AS user_id,
                           p.id AS principal_id,
                           u.handle,
                           u.display_name,
                           u.bio,
                           u.profile_image_url,
                           u.display_community_id,
                           dc.name AS display_community_name,
                           u.display_specialization_id,
                           ds.name AS display_specialization_name,
                           u.created_at,
                           COALESCE(mutual.mutual_count, 0) AS mutual_count,
                           (cfm.candidate_principal_id IS NOT NULL) AS follows_viewer,
                           COALESCE(shared_communities.shared_count, 0) AS shared_community_count,
                           (tcm.user_id IS NOT NULL) AS in_target_community,
                           COALESCE(shared_specs.shared_count, 0) AS shared_specialization_count,
                           COALESCE(activity.recent_posts, 0) AS recent_posts,
                           COALESCE(activity.target_recent_posts, 0) AS target_recent_posts,
                           COALESCE((
                               SELECT COUNT(*)
                               FROM people_reco_suppressions ls
                               JOIN users su ON su.id = ls.candidate_user_id
                               JOIN me m2 ON true
                               WHERE ls.viewer_user_id = m2.viewer_user_id
                                 AND ls.suppression_type = 'less_like_this'
                                 AND ls.expires_at > m2.as_of
                                 AND su.display_community_id IS NOT NULL
                                 AND su.display_community_id = u.display_community_id
                           ), 0) AS less_like_community_hits,
                           COALESCE((
                               SELECT COUNT(*)
                               FROM people_reco_suppressions ls
                               JOIN users su ON su.id = ls.candidate_user_id
                               JOIN me m2 ON true
                               WHERE ls.viewer_user_id = m2.viewer_user_id
                                 AND ls.suppression_type = 'less_like_this'
                                 AND ls.expires_at > m2.as_of
                                 AND su.display_specialization_id IS NOT NULL
                                 AND su.display_specialization_id = u.display_specialization_id
                           ), 0) AS less_like_spec_hits,
                           COALESCE(viewer_exposure.viewer_exposure_count, 0) AS viewer_exposure_count
                    FROM users u
                    LEFT JOIN principals p ON p.user_id = u.id AND p.kind = 'user'
                    CROSS JOIN me
                    LEFT JOIN mutual ON mutual.candidate_principal_id = p.id
                    LEFT JOIN candidate_follows_me cfm ON cfm.candidate_principal_id = p.id
                    LEFT JOIN shared_communities ON shared_communities.user_id = u.id
                    LEFT JOIN target_community_members tcm ON tcm.user_id = u.id
                    LEFT JOIN shared_specs ON shared_specs.user_id = u.id
                    LEFT JOIN activity ON activity.user_id = u.id
                    LEFT JOIN viewer_hidden vh ON vh.candidate_user_id = u.id
                    LEFT JOIN viewer_reported vr ON vr.user_id = u.id
                    LEFT JOIN open_reports obr ON obr.user_id = u.id
                    LEFT JOIN viewer_exposure ON viewer_exposure.user_id = u.id
                    LEFT JOIN communities dc ON dc.id = u.display_community_id
                    LEFT JOIN communities ds ON ds.id = u.display_specialization_id AND ds.kind = 'specialization'
                    LEFT JOIN viewer_following vf ON p.id IS NOT NULL AND vf.followee_principal_id = p.id
                    WHERE u.company_id = me.company_id
                      AND u.deleted_at IS NULL
                      AND u.disabled_at IS NULL
                      AND u.id <> me.viewer_user_id
                      AND vf.followee_principal_id IS NULL
                      AND vh.candidate_user_id IS NULL
                      AND vr.user_id IS NULL
                      AND COALESCE(obr.open_reporters, 0) < ?
                      AND COALESCE(viewer_exposure.viewer_exposure_count, 0) < ?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM principal_blocks b
                          WHERE (b.blocker_principal_id = me.viewer_principal_id AND b.blocked_principal_id = p.id)
                             OR (p.id IS NOT NULL AND b.blocker_principal_id = p.id AND b.blocked_principal_id = me.viewer_principal_id)
                      )
                      AND __RAIL_CONDITION__
                ),
                ranked AS (
                    SELECT b.*,
                           CAST((
                               COALESCE(mutual_count, 0) * 90000 +
                               COALESCE(shared_community_count, 0) * 60000 +
                               COALESCE(shared_specialization_count, 0) * 50000 +
                               CASE WHEN follows_viewer THEN 25000 ELSE 0 END +
                               COALESCE(recent_posts, 0) * 4000 +
                               LEAST(40000, (1.0 / (1.0 + EXTRACT(EPOCH FROM ((SELECT as_of FROM me) - created_at)) / 86400.0)) * 40000) +
                               __RAIL_BOOST__ -
                               COALESCE(less_like_community_hits, 0) * 45000 -
                               COALESCE(less_like_spec_hits, 0) * 35000 -
                               COALESCE(viewer_exposure_count, 0) * 60000
                           ) AS BIGINT) AS score
                    FROM base b
                )
                SELECT *
                FROM ranked
                """;
        String sql = sqlTemplate
                .replace("__RAIL_CONDITION__", railCondition)
                .replace("__RAIL_BOOST__", railBoostExpr);

        List<Object> params = new ArrayList<>();
        params.add(ctx.viewerUserId());
        params.add(ctx.viewerPrincipalId());
        params.add(ctx.companyId());
        params.add(ctx.communityId());
        params.add(ctx.asOf());
        params.add(Math.max(1, activeWindowDays));
        params.add(Math.max(1, activeWindowDays));
        params.add(Math.max(1, openReportExclusionThreshold));
        params.add(Math.max(1, maxViewerExposurePerCandidate24h));

        if (cursor != null && cursor.score() != null && cursor.createdAt() != null && cursor.userId() != null) {
            sql += " WHERE (score < ? OR (score = ? AND (created_at < ? OR (created_at = ? AND user_id < ?)))) ";
            params.add(cursor.score());
            params.add(cursor.score());
            params.add(cursor.createdAt());
            params.add(cursor.createdAt());
            params.add(cursor.userId());
        }

        sql += " ORDER BY score DESC, created_at DESC, user_id DESC LIMIT ?";
        params.add(limit);

        return jdbc.query(sql, (rs, rowNum) -> {
            CandidateRow row = new CandidateRow();
            row.userId = rs.getLong("user_id");
            row.principalId = rs.getLong("principal_id");
            row.handle = rs.getString("handle");
            row.displayName = rs.getString("display_name");
            row.bio = rs.getString("bio");
            row.profileImageUrl = rs.getString("profile_image_url");
            Long displayCommunityId = rs.getLong("display_community_id");
            row.displayCommunityId = rs.wasNull() ? null : displayCommunityId;
            row.displayCommunityName = rs.getString("display_community_name");
            Long displaySpecializationId = rs.getLong("display_specialization_id");
            row.displaySpecializationId = rs.wasNull() ? null : displaySpecializationId;
            row.displaySpecializationName = rs.getString("display_specialization_name");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.mutualCount = rs.getInt("mutual_count");
            row.followsViewer = rs.getBoolean("follows_viewer");
            row.sharedCommunityCount = rs.getInt("shared_community_count");
            row.inTargetCommunity = rs.getBoolean("in_target_community");
            row.sharedSpecializationCount = rs.getInt("shared_specialization_count");
            row.recentPosts = rs.getInt("recent_posts");
            row.targetRecentPosts = rs.getInt("target_recent_posts");
            row.lessLikeCommunityHits = rs.getInt("less_like_community_hits");
            row.lessLikeSpecHits = rs.getInt("less_like_spec_hits");
            row.viewerExposureCount = rs.getInt("viewer_exposure_count");
            row.score = rs.getLong("score");
            return row;
        }, params.toArray());
    }

    List<CandidateRow> findFallbackCandidates(PeopleRecommendationTypes.Rail rail,
                                              ViewerContext ctx,
                                              int limit,
                                              int activeWindowDays,
                                              int openReportExclusionThreshold) {
        String baseCondition = switch (rail) {
            case PYMK -> "true";
            case COMMUNITY -> "(me.target_community_id IS NOT NULL AND EXISTS (" +
                    "SELECT 1 FROM community_verifications cv " +
                    "WHERE cv.user_id = u.id AND cv.community_id = me.target_community_id " +
                    "AND cv.verified = true AND (cv.expires_at IS NULL OR cv.expires_at > me.as_of)" +
                    "))";
            case ACTIVE_COMMUNITY -> "(me.target_community_id IS NOT NULL AND COALESCE(a.target_recent_posts, 0) > 0)";
        };

        String fallbackTemplate = """
                WITH me AS (
                    SELECT ?::bigint AS viewer_user_id,
                           ?::bigint AS viewer_principal_id,
                           ?::bigint AS company_id,
                           ?::bigint AS target_community_id,
                           ?::timestamptz AS as_of
                ),
                activity AS (
                    SELECT p.author_id AS user_id,
                           COUNT(*) FILTER (
                               WHERE p.created_at >= me.as_of - (? * interval '1 day')
                           )::int AS recent_posts,
                           COUNT(*) FILTER (
                               WHERE me.target_community_id IS NOT NULL
                                 AND p.community_id = me.target_community_id
                                 AND p.created_at >= me.as_of - (? * interval '1 day')
                           )::int AS target_recent_posts
                    FROM posts p
                    CROSS JOIN me
                    WHERE p.author_id IS NOT NULL
                      AND p.created_at <= me.as_of
                    GROUP BY p.author_id
                ),
                viewer_reported AS (
                    SELECT DISTINCT r.target_id AS user_id
                    FROM reports r
                    JOIN me ON r.reporter_id = me.viewer_user_id
                    WHERE r.target_type = 'user'
                      AND r.status <> 'dismissed'
                ),
                open_reports AS (
                    SELECT r.target_id AS user_id,
                           COUNT(DISTINCT r.reporter_id)::int AS open_reporters
                    FROM reports r
                    WHERE r.target_type = 'user'
                      AND r.status = 'open'
                    GROUP BY r.target_id
                )
                SELECT u.id AS user_id,
                       p.id AS principal_id,
                       u.handle,
                       u.display_name,
                       u.bio,
                       u.profile_image_url,
                       u.display_community_id,
                       dc.name AS display_community_name,
                       u.display_specialization_id,
                       ds.name AS display_specialization_name,
                       u.created_at,
                       0 AS mutual_count,
                       false AS follows_viewer,
                       0 AS shared_community_count,
                       false AS in_target_community,
                       0 AS shared_specialization_count,
                       COALESCE(a.recent_posts, 0) AS recent_posts,
                       COALESCE(a.target_recent_posts, 0) AS target_recent_posts,
                       0 AS less_like_community_hits,
                       0 AS less_like_spec_hits,
                       0 AS viewer_exposure_count,
                       CAST((
                           COALESCE(a.target_recent_posts, 0) * 70000 +
                           COALESCE(a.recent_posts, 0) * 5000 +
                           LEAST(30000, (1.0 / (1.0 + EXTRACT(EPOCH FROM ((SELECT as_of FROM me) - u.created_at)) / 86400.0)) * 30000)
                       ) AS BIGINT) AS score
                FROM users u
                LEFT JOIN principals p ON p.user_id = u.id AND p.kind = 'user'
                CROSS JOIN me
                LEFT JOIN activity a ON a.user_id = u.id
                LEFT JOIN viewer_reported vr ON vr.user_id = u.id
                LEFT JOIN open_reports obr ON obr.user_id = u.id
                LEFT JOIN communities dc ON dc.id = u.display_community_id
                LEFT JOIN communities ds ON ds.id = u.display_specialization_id AND ds.kind = 'specialization'
                WHERE u.company_id = me.company_id
                  AND u.deleted_at IS NULL
                  AND u.disabled_at IS NULL
                  AND u.id <> me.viewer_user_id
                  AND vr.user_id IS NULL
                  AND COALESCE(obr.open_reporters, 0) < ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM principal_follows f
                      WHERE f.follower_principal_id = me.viewer_principal_id
                        AND p.id IS NOT NULL
                        AND f.followee_principal_id = p.id
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM principal_blocks b
                      WHERE (b.blocker_principal_id = me.viewer_principal_id AND b.blocked_principal_id = p.id)
                         OR (p.id IS NOT NULL AND b.blocker_principal_id = p.id AND b.blocked_principal_id = me.viewer_principal_id)
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM people_reco_suppressions s
                      WHERE s.viewer_user_id = me.viewer_user_id
                        AND s.candidate_user_id = u.id
                        AND s.expires_at > me.as_of
                        AND s.suppression_type IN ('hide', 'less_like_this')
                  )
                  AND __BASE_CONDITION__
                ORDER BY score DESC, u.created_at DESC, u.id DESC
                LIMIT ?
                """;
        String sql = fallbackTemplate.replace("__BASE_CONDITION__", baseCondition);

        return jdbc.query(sql, (rs, rowNum) -> {
            CandidateRow row = new CandidateRow();
            row.userId = rs.getLong("user_id");
            row.principalId = rs.getLong("principal_id");
            row.handle = rs.getString("handle");
            row.displayName = rs.getString("display_name");
            row.bio = rs.getString("bio");
            row.profileImageUrl = rs.getString("profile_image_url");
            Long displayCommunityId = rs.getLong("display_community_id");
            row.displayCommunityId = rs.wasNull() ? null : displayCommunityId;
            row.displayCommunityName = rs.getString("display_community_name");
            Long displaySpecializationId = rs.getLong("display_specialization_id");
            row.displaySpecializationId = rs.wasNull() ? null : displaySpecializationId;
            row.displaySpecializationName = rs.getString("display_specialization_name");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.mutualCount = rs.getInt("mutual_count");
            row.followsViewer = rs.getBoolean("follows_viewer");
            row.sharedCommunityCount = rs.getInt("shared_community_count");
            row.inTargetCommunity = rs.getBoolean("in_target_community");
            row.sharedSpecializationCount = rs.getInt("shared_specialization_count");
            row.recentPosts = rs.getInt("recent_posts");
            row.targetRecentPosts = rs.getInt("target_recent_posts");
            row.lessLikeCommunityHits = rs.getInt("less_like_community_hits");
            row.lessLikeSpecHits = rs.getInt("less_like_spec_hits");
            row.viewerExposureCount = rs.getInt("viewer_exposure_count");
            row.score = rs.getLong("score");
            return row;
        },
                ctx.viewerUserId(),
                ctx.viewerPrincipalId(),
                ctx.companyId(),
                ctx.communityId(),
                ctx.asOf(),
                Math.max(1, activeWindowDays),
                Math.max(1, activeWindowDays),
                Math.max(1, openReportExclusionThreshold),
                limit
        );
    }

    void insertServedAuditBatch(List<ServedAuditInsert> rows) {
        if (rows == null || rows.isEmpty()) return;
        jdbc.batchUpdate(
                "INSERT INTO people_reco_served_audit(" +
                        "request_id, viewer_user_id, candidate_user_id, rail, surface, recommendation_id, tracking_token, " +
                        "reason_codes, reason_texts, rank_score, position, model_version, experiment_key, experiment_bucket" +
                        ") VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?)",
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setObject(1, row.requestId());
                    ps.setLong(2, row.viewerUserId());
                    ps.setLong(3, row.candidateUserId());
                    ps.setString(4, row.rail());
                    ps.setString(5, row.surface());
                    ps.setString(6, row.recommendationId());
                    ps.setString(7, row.trackingToken());
                    ps.setString(8, toJson(row.reasonCodes()));
                    ps.setString(9, toJson(row.reasonTexts()));
                    ps.setLong(10, row.rankScore());
                    ps.setInt(11, row.position());
                    ps.setString(12, row.modelVersion());
                    ps.setString(13, row.experimentKey());
                    ps.setString(14, row.experimentBucket());
                }
        );
    }

    Optional<ServedLookup> findServedByTrackingToken(long viewerUserId, String trackingToken, String recommendationId) {
        if (trackingToken == null || trackingToken.isBlank()) return Optional.empty();
        List<ServedLookup> rows;
        if (recommendationId != null && !recommendationId.isBlank()) {
            rows = jdbc.query(
                    "SELECT candidate_user_id, rail, surface, recommendation_id, tracking_token " +
                            "FROM people_reco_served_audit WHERE viewer_user_id = ? AND tracking_token = ? AND recommendation_id = ? " +
                            "ORDER BY created_at DESC, id DESC LIMIT 1",
                    (rs, rowNum) -> new ServedLookup(
                            rs.getLong("candidate_user_id"),
                            rs.getString("rail"),
                            rs.getString("surface"),
                            rs.getString("recommendation_id"),
                            rs.getString("tracking_token")
                    ),
                    viewerUserId,
                    trackingToken,
                    recommendationId
            );
        } else {
            rows = jdbc.query(
                    "SELECT candidate_user_id, rail, surface, recommendation_id, tracking_token " +
                            "FROM people_reco_served_audit WHERE viewer_user_id = ? AND tracking_token = ? " +
                            "ORDER BY created_at DESC, id DESC LIMIT 1",
                    (rs, rowNum) -> new ServedLookup(
                            rs.getLong("candidate_user_id"),
                            rs.getString("rail"),
                            rs.getString("surface"),
                            rs.getString("recommendation_id"),
                            rs.getString("tracking_token")
                    ),
                    viewerUserId,
                    trackingToken
            );
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    boolean insertFeedbackEventIfAbsent(FeedbackInsert row) {
        try {
            int changed = jdbc.update(
                    "INSERT INTO people_reco_feedback_events(" +
                            "event_id, viewer_user_id, candidate_user_id, recommendation_id, tracking_token, rail, surface, event_type, position, metadata, client_ts" +
                            ") VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT (event_id) DO NOTHING",
                    row.eventId(),
                    row.viewerUserId(),
                    row.candidateUserId(),
                    row.recommendationId(),
                    row.trackingToken(),
                    row.rail(),
                    row.surface(),
                    row.eventType(),
                    row.position(),
                    toJson(row.metadata()),
                    row.clientTs()
            );
            return changed > 0;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    void upsertSuppression(long viewerUserId, long candidateUserId, String suppressionType, OffsetDateTime expiresAt) {
        jdbc.update(
                "INSERT INTO people_reco_suppressions(viewer_user_id, candidate_user_id, suppression_type, expires_at, created_at, updated_at) " +
                        "VALUES (?,?,?,?, now(), now()) " +
                        "ON CONFLICT (viewer_user_id, candidate_user_id, suppression_type) " +
                        "DO UPDATE SET expires_at = EXCLUDED.expires_at, updated_at = now()",
                viewerUserId,
                candidateUserId,
                suppressionType,
                expiresAt
        );
    }

    int deleteExpiredSuppressions(int batchSize) {
        int size = Math.max(1, batchSize);
        Integer deleted = jdbc.queryForObject(
                "WITH doomed AS (" +
                        "SELECT viewer_user_id, candidate_user_id, suppression_type " +
                        "FROM people_reco_suppressions WHERE expires_at <= now() " +
                        "ORDER BY expires_at ASC LIMIT ?" +
                        ") DELETE FROM people_reco_suppressions s USING doomed d " +
                        "WHERE s.viewer_user_id = d.viewer_user_id " +
                        "AND s.candidate_user_id = d.candidate_user_id " +
                        "AND s.suppression_type = d.suppression_type",
                Integer.class,
                size
        );
        return deleted == null ? 0 : deleted;
    }

    int deleteOldAudit(OffsetDateTime cutoff, int batchSize) {
        int size = Math.max(1, batchSize);
        Integer deleted = jdbc.queryForObject(
                "WITH doomed AS (" +
                        "SELECT id FROM people_reco_served_audit WHERE created_at < ? " +
                        "ORDER BY created_at ASC, id ASC LIMIT ?" +
                        ") DELETE FROM people_reco_served_audit a USING doomed d WHERE a.id = d.id",
                Integer.class,
                cutoff,
                size
        );
        return deleted == null ? 0 : deleted;
    }

    int deleteOldFeedback(OffsetDateTime cutoff, int batchSize) {
        int size = Math.max(1, batchSize);
        Integer deleted = jdbc.queryForObject(
                "WITH doomed AS (" +
                        "SELECT id FROM people_reco_feedback_events WHERE created_at < ? " +
                        "ORDER BY created_at ASC, id ASC LIMIT ?" +
                        ") DELETE FROM people_reco_feedback_events a USING doomed d WHERE a.id = d.id",
                Integer.class,
                cutoff,
                size
        );
        return deleted == null ? 0 : deleted;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize recommendation payload", e);
        }
    }

    record ViewerContext(long viewerUserId,
                         long viewerPrincipalId,
                         long companyId,
                         Long communityId,
                         OffsetDateTime asOf) {}

    record CursorKeyset(Long score, OffsetDateTime createdAt, Long userId) {}

    record ServedAuditInsert(java.util.UUID requestId,
                             long viewerUserId,
                             long candidateUserId,
                             String rail,
                             String surface,
                             String recommendationId,
                             String trackingToken,
                             List<String> reasonCodes,
                             List<String> reasonTexts,
                             long rankScore,
                             int position,
                             String modelVersion,
                             String experimentKey,
                             String experimentBucket) {}

    record ServedLookup(long candidateUserId,
                        String rail,
                        String surface,
                        String recommendationId,
                        String trackingToken) {}

    record FeedbackInsert(String eventId,
                          long viewerUserId,
                          Long candidateUserId,
                          String recommendationId,
                          String trackingToken,
                          String rail,
                          String surface,
                          String eventType,
                          Integer position,
                          java.util.Map<String, Object> metadata,
                          OffsetDateTime clientTs) {}

    static class CandidateRow {
        long userId;
        long principalId;
        String handle;
        String displayName;
        String bio;
        String profileImageUrl;
        Long displayCommunityId;
        String displayCommunityName;
        Long displaySpecializationId;
        String displaySpecializationName;
        OffsetDateTime createdAt;
        int mutualCount;
        boolean followsViewer;
        int sharedCommunityCount;
        boolean inTargetCommunity;
        int sharedSpecializationCount;
        int recentPosts;
        int targetRecentPosts;
        int lessLikeCommunityHits;
        int lessLikeSpecHits;
        int viewerExposureCount;
        long score;
    }
}
