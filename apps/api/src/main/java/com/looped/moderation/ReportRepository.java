package com.looped.moderation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ReportRepository {
    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ReportRow> MAPPER = new RowMapper<>() {
        @Override
        public ReportRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ReportRow r = new ReportRow();
            r.id = rs.getLong("id");
            r.targetType = rs.getString("target_type");
            r.targetId = rs.getLong("target_id");
            r.reporterId = rs.getLong("reporter_id");
            r.reason = rs.getString("reason");
            r.status = rs.getString("status");
            r.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            r.updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
            return r;
        }
    };

    public long insert(String targetType, long targetId, long reporterId, String reason) {
        Long id = jdbc.query(
                "INSERT INTO reports(target_type, target_id, reporter_id, reason) VALUES (?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                targetType, targetId, reporterId, reason
        );
        return id;
    }

    public Optional<ReportRow> findById(long id) {
        var list = jdbc.query("SELECT * FROM reports WHERE id=?", MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<ReportRow> listByReporter(long reporterId, String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT * FROM reports WHERE reporter_id=? ORDER BY created_at DESC", MAPPER, reporterId);
        }
        return jdbc.query("SELECT * FROM reports WHERE reporter_id=? AND status=? ORDER BY created_at DESC", MAPPER, reporterId, status);
    }

    public boolean updateStatus(long id, String newStatus) {
        int rows = jdbc.update("UPDATE reports SET status=?, updated_at=now() WHERE id=?", newStatus, id);
        return rows > 0;
    }

    public static class ReportRow {
        public long id;
        public String targetType;
        public long targetId;
        public long reporterId;
        public String reason;
        public String status;
        public OffsetDateTime createdAt;
        public OffsetDateTime updatedAt;
    }
}

