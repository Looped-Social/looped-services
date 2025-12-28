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
public class AppealRepository {
    private final JdbcTemplate jdbc;

    public AppealRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AppealRow> MAPPER = new RowMapper<>() {
        @Override
        public AppealRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            AppealRow row = new AppealRow();
            row.id = rs.getLong("id");
            row.userId = rs.getLong("user_id");
            row.targetType = rs.getString("target_type");
            row.targetId = rs.getLong("target_id");
            row.reason = rs.getString("reason");
            row.status = rs.getString("status");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
            row.reviewedAt = rs.getObject("reviewed_at", OffsetDateTime.class);
            long reviewedBy = rs.getLong("reviewed_by");
            row.reviewedBy = rs.wasNull() ? null : reviewedBy;
            row.reviewedReason = rs.getString("reviewed_reason");
            return row;
        }
    };

    private static final RowMapper<AppealRow> ADMIN_MAPPER = new RowMapper<>() {
        @Override
        public AppealRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            AppealRow row = MAPPER.mapRow(rs, rowNum);
            row.userHandle = rs.getString("user_handle");
            return row;
        }
    };

    public long insert(long userId, String targetType, long targetId, String reason) {
        Long id = jdbc.query(
                "INSERT INTO appeals(user_id, target_type, target_id, reason) VALUES (?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId, targetType, targetId, reason
        );
        if (id == null) {
            throw new IllegalStateException("Failed to insert appeal");
        }
        return id;
    }

    public Optional<AppealRow> findById(long id) {
        var list = jdbc.query("SELECT * FROM appeals WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<AppealRow> listByUser(long userId, String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT * FROM appeals WHERE user_id = ? ORDER BY created_at DESC", MAPPER, userId);
        }
        return jdbc.query(
                "SELECT * FROM appeals WHERE user_id = ? AND status = ? ORDER BY created_at DESC",
                MAPPER, userId, status
        );
    }

    public boolean review(long id, String status, Long reviewedBy, String reviewedReason) {
        int rows = jdbc.update(
                "UPDATE appeals SET status = ?, reviewed_at = now(), reviewed_by = ?, reviewed_reason = ?, " +
                        "updated_at = now() WHERE id = ?",
                status, reviewedBy, reviewedReason, id
        );
        return rows > 0;
    }

    public List<AppealRow> listAll(String status, String targetType, Long userId,
                                   OffsetDateTime cursorTs, Long cursorId, int limit, boolean ascending) {
        String base = "SELECT a.*, u.handle AS user_handle FROM appeals a " +
                "LEFT JOIN users u ON u.id = a.user_id ";
        StringBuilder where = new StringBuilder();
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append("a.status = ?");
            params.add(status);
        }
        if (targetType != null && !targetType.isBlank()) {
            if (!where.isEmpty()) where.append(" AND ");
            where.append("a.target_type = ?");
            params.add(targetType);
        }
        if (userId != null) {
            if (!where.isEmpty()) where.append(" AND ");
            where.append("a.user_id = ?");
            params.add(userId);
        }
        if (cursorTs != null && cursorId != null) {
            if (!where.isEmpty()) where.append(" AND ");
            if (ascending) {
                where.append("(a.created_at > ? OR (a.created_at = ? AND a.id > ?))");
            } else {
                where.append("(a.created_at < ? OR (a.created_at = ? AND a.id < ?))");
            }
            params.add(cursorTs);
            params.add(cursorTs);
            params.add(cursorId);
        }
        String order = ascending ? "ASC" : "DESC";
        String sql = base + (where.isEmpty() ? "" : "WHERE " + where + " ") +
                "ORDER BY a.created_at " + order + ", a.id " + order + " LIMIT ?";
        params.add(limit);
        return jdbc.query(sql, ADMIN_MAPPER, params.toArray());
    }

    public static class AppealRow {
        public long id;
        public long userId;
        public String userHandle;
        public String targetType;
        public long targetId;
        public String reason;
        public String status;
        public OffsetDateTime createdAt;
        public OffsetDateTime updatedAt;
        public OffsetDateTime reviewedAt;
        public Long reviewedBy;
        public String reviewedReason;
    }
}
