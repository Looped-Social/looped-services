package com.looped.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class AdminOpsAlertsRepository {
    private final JdbcTemplate jdbc;

    public AdminOpsAlertsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<BacklogSnapshot> MAPPER = new RowMapper<>() {
        @Override
        public BacklogSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            BacklogSnapshot out = new BacklogSnapshot();
            out.verificationsPending = rs.getLong("verifications_pending");
            out.verificationsOldestPendingAt = rs.getObject("verifications_oldest_pending_at", OffsetDateTime.class);
            out.verificationsSubmitted24h = rs.getLong("verifications_submitted_24h");
            out.verificationsReviewed24h = rs.getLong("verifications_reviewed_24h");

            out.reportsOpen = rs.getLong("reports_open");
            out.reportsOldestOpenAt = rs.getObject("reports_oldest_open_at", OffsetDateTime.class);
            out.reportsCreated24h = rs.getLong("reports_created_24h");
            out.reportsReviewed24h = rs.getLong("reports_reviewed_24h");

            out.moderationQueueOpen = rs.getLong("moderation_queue_open");
            out.moderationQueueOldestOpenAt = rs.getObject("moderation_queue_oldest_open_at", OffsetDateTime.class);
            out.moderationQueueCreated24h = rs.getLong("moderation_queue_created_24h");
            out.moderationQueueReviewed24h = rs.getLong("moderation_queue_reviewed_24h");
            return out;
        }
    };

    public BacklogSnapshot snapshot() {
        String sql = """
                SELECT
                    (SELECT COUNT(*) FROM verification_requests vr WHERE vr.status = 'pending') AS verifications_pending,
                    (SELECT MIN(vr.submitted_at) FROM verification_requests vr WHERE vr.status = 'pending') AS verifications_oldest_pending_at,
                    (SELECT COUNT(*) FROM verification_requests vr WHERE vr.submitted_at >= now() - interval '24 hours') AS verifications_submitted_24h,
                    (SELECT COUNT(*) FROM verification_requests vr WHERE vr.reviewed_at >= now() - interval '24 hours') AS verifications_reviewed_24h,

                    (SELECT COUNT(*) FROM reports r WHERE r.status = 'open') AS reports_open,
                    (SELECT MIN(r.created_at) FROM reports r WHERE r.status = 'open') AS reports_oldest_open_at,
                    (SELECT COUNT(*) FROM reports r WHERE r.created_at >= now() - interval '24 hours') AS reports_created_24h,
                    (SELECT COUNT(*) FROM reports r WHERE r.resolved_at >= now() - interval '24 hours') AS reports_reviewed_24h,

                    (SELECT COUNT(*) FROM moderation_queue_items mqi WHERE mqi.status = 'open') AS moderation_queue_open,
                    (SELECT MIN(mqi.created_at) FROM moderation_queue_items mqi WHERE mqi.status = 'open') AS moderation_queue_oldest_open_at,
                    (SELECT COUNT(*) FROM moderation_queue_items mqi WHERE mqi.created_at >= now() - interval '24 hours') AS moderation_queue_created_24h,
                    (SELECT COUNT(*) FROM moderation_queue_items mqi WHERE mqi.reviewed_at >= now() - interval '24 hours') AS moderation_queue_reviewed_24h
                """;
        List<BacklogSnapshot> rows = jdbc.query(sql, MAPPER);
        return rows.isEmpty() ? new BacklogSnapshot() : rows.get(0);
    }

    public static class BacklogSnapshot {
        public long verificationsPending;
        public OffsetDateTime verificationsOldestPendingAt;
        public long verificationsSubmitted24h;
        public long verificationsReviewed24h;

        public long reportsOpen;
        public OffsetDateTime reportsOldestOpenAt;
        public long reportsCreated24h;
        public long reportsReviewed24h;

        public long moderationQueueOpen;
        public OffsetDateTime moderationQueueOldestOpenAt;
        public long moderationQueueCreated24h;
        public long moderationQueueReviewed24h;
    }
}
