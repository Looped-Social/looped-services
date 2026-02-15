package com.looped.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class AdminFypScaleAlertsRepository {
    private final JdbcTemplate jdbc;

    public AdminFypScaleAlertsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FypScaleSnapshot> MAPPER = new RowMapper<>() {
        @Override
        public FypScaleSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            FypScaleSnapshot out = new FypScaleSnapshot();
            out.postsCreated24h = rs.getLong("posts_created_24h");
            out.telemetryEvents24h = rs.getLong("telemetry_events_24h");
            out.feedImpressions24h = rs.getLong("feed_impressions_24h");
            out.feedImpressionsInteractable24h = rs.getLong("feed_impressions_interactable_24h");
            out.telemetryUsers24h = rs.getLong("telemetry_users_24h");
            out.feedRequestIds24h = rs.getLong("feed_request_ids_24h");
            out.globalFypRequestIds24h = rs.getLong("global_fyp_request_ids_24h");
            out.avgVisibleMs24h = rs.getDouble("avg_visible_ms_24h");
            out.interactionBlocked24h = rs.getLong("interaction_blocked_24h");
            out.communityJoinIntent24h = rs.getLong("community_join_intent_24h");
            out.communityVerifyIntent24h = rs.getLong("community_verify_intent_24h");
            out.telemetryTotalBytes = rs.getLong("telemetry_total_bytes");
            out.databaseBytes = rs.getLong("database_bytes");
            return out;
        }
    };

    public FypScaleSnapshot snapshot() {
        String sql = """
                SELECT
                    (SELECT COUNT(*) FROM posts p
                     WHERE p.created_at >= now() - interval '24 hours'
                       AND p.removed_at IS NULL
                       AND p.visibility = 'public') AS posts_created_24h,

                    (SELECT COUNT(*) FROM telemetry_events te
                     WHERE te.occurred_at >= now() - interval '24 hours') AS telemetry_events_24h,

                    (SELECT COUNT(*) FROM telemetry_events te
                     WHERE te.type = 'feed_impression'
                       AND te.occurred_at >= now() - interval '24 hours') AS feed_impressions_24h,

                    (SELECT COUNT(*) FROM telemetry_events te
                     WHERE te.type = 'feed_impression'
                       AND te.occurred_at >= now() - interval '24 hours'
                       AND te.payload->>'can_interact' = 'true') AS feed_impressions_interactable_24h,

                    (SELECT COUNT(DISTINCT te.user_id) FROM telemetry_events te
                     WHERE te.occurred_at >= now() - interval '24 hours') AS telemetry_users_24h,

                    (SELECT COUNT(DISTINCT te.feed_request_id) FROM telemetry_events te
                     WHERE te.feed_request_id IS NOT NULL
                       AND te.occurred_at >= now() - interval '24 hours') AS feed_request_ids_24h,

                    (SELECT COUNT(DISTINCT te.feed_request_id) FROM telemetry_events te
                     WHERE te.feed_request_id IS NOT NULL
                       AND te.occurred_at >= now() - interval '24 hours'
                       AND te.feed_mode = 'for_you'
                       AND te.feed_community_id IS NULL) AS global_fyp_request_ids_24h,

                    (SELECT COALESCE(AVG((te.payload->>'visible_ms')::int), 0.0)
                     FROM telemetry_events te
                     WHERE te.type = 'feed_impression'
                       AND te.occurred_at >= now() - interval '24 hours'
                       AND te.payload ? 'visible_ms') AS avg_visible_ms_24h,

                    (SELECT COUNT(*) FROM telemetry_events te
                     WHERE te.type = 'interaction_blocked'
                       AND te.occurred_at >= now() - interval '24 hours') AS interaction_blocked_24h,

                    (SELECT COUNT(*) FROM telemetry_events te
                     WHERE te.type = 'community_join_intent'
                       AND te.occurred_at >= now() - interval '24 hours') AS community_join_intent_24h,

                    (SELECT COUNT(*) FROM telemetry_events te
                     WHERE te.type = 'community_verify_intent'
                       AND te.occurred_at >= now() - interval '24 hours') AS community_verify_intent_24h,

                    COALESCE(pg_total_relation_size('public.telemetry_events'), 0) AS telemetry_total_bytes,
                    COALESCE(pg_database_size(current_database()), 0) AS database_bytes
                """;
        List<FypScaleSnapshot> rows = jdbc.query(sql, MAPPER);
        return rows.isEmpty() ? new FypScaleSnapshot() : rows.get(0);
    }

    public static class FypScaleSnapshot {
        public long postsCreated24h;
        public long telemetryEvents24h;
        public long feedImpressions24h;
        public long feedImpressionsInteractable24h;
        public long telemetryUsers24h;
        public long feedRequestIds24h;
        public long globalFypRequestIds24h;
        public double avgVisibleMs24h;
        public long interactionBlocked24h;
        public long communityJoinIntent24h;
        public long communityVerifyIntent24h;
        public long telemetryTotalBytes;
        public long databaseBytes;

        public double interactableImpressionShare24h() {
            if (feedImpressions24h <= 0) return 0.0d;
            return (double) feedImpressionsInteractable24h / (double) feedImpressions24h;
        }
    }
}

