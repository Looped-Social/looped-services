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
            r.resolvedAt = rs.getObject("resolved_at", OffsetDateTime.class);
            long resolvedBy = rs.getLong("resolved_by");
            r.resolvedBy = rs.wasNull() ? null : resolvedBy;
            r.resolvedReason = rs.getString("resolved_reason");
            return r;
        }
    };

    private static final RowMapper<ReportRow> ADMIN_MAPPER = new RowMapper<>() {
        @Override
        public ReportRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ReportRow r = MAPPER.mapRow(rs, rowNum);
            r.reporterHandle = rs.getString("reporter_handle");
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

    public boolean resolve(long id, Long resolvedBy, String resolvedReason) {
        int rows = jdbc.update(
                "UPDATE reports SET status = 'resolved', resolved_at = now(), resolved_by = ?, " +
                        "resolved_reason = ?, updated_at = now() WHERE id = ?",
                resolvedBy, resolvedReason, id
        );
        return rows > 0;
    }

    public List<ReportRow> listAll(String status, String targetType, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = "SELECT r.*, u.handle AS reporter_handle FROM reports r " +
                "LEFT JOIN users u ON u.id = r.reporter_id ";
        StringBuilder where = new StringBuilder();
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append("r.status = ?");
            params.add(status);
        }
        if (targetType != null && !targetType.isBlank()) {
            if (!where.isEmpty()) where.append(" AND ");
            where.append("r.target_type = ?");
            params.add(targetType);
        }
        if (cursorTs != null && cursorId != null) {
            if (!where.isEmpty()) where.append(" AND ");
            where.append("(r.created_at < ? OR (r.created_at = ? AND r.id < ?))");
            params.add(cursorTs);
            params.add(cursorTs);
            params.add(cursorId);
        }
        String sql = base + (where.isEmpty() ? "" : "WHERE " + where + " ") +
                "ORDER BY r.created_at DESC, r.id DESC LIMIT ?";
        params.add(limit);
        return jdbc.query(sql, ADMIN_MAPPER, params.toArray());
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
        public OffsetDateTime resolvedAt;
        public Long resolvedBy;
        public String resolvedReason;
        public String reporterHandle;
    }
}
