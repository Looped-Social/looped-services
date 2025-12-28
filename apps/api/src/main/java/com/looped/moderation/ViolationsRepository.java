package com.looped.moderation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class ViolationsRepository {
    private final JdbcTemplate jdbc;

    public ViolationsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ViolationRow> MAPPER = new RowMapper<>() {
        @Override
        public ViolationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ViolationRow row = new ViolationRow();
            row.targetType = rs.getString("target_type");
            row.targetId = rs.getLong("target_id");
            row.reason = rs.getString("reason");
            row.status = rs.getString("status");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public List<ViolationRow> list(long userId, long authorPrincipalId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = "SELECT target_type, target_id, reason, status, created_at FROM (" +
                "SELECT 'post_removal' AS target_type, p.id AS target_id, p.removed_reason AS reason, " +
                "'removed' AS status, p.removed_at AS created_at " +
                "FROM posts p WHERE p.author_principal_id = ? AND p.removed_at IS NOT NULL " +
                "UNION ALL " +
                "SELECT 'user_ban' AS target_type, b.id AS target_id, b.reason AS reason, " +
                "'active' AS status, b.created_at AS created_at " +
                "FROM user_bans b WHERE b.user_id = ? AND b.revoked_at IS NULL " +
                "AND (b.expires_at IS NULL OR b.expires_at > now())" +
                ") v";
        if (cursorTs == null || cursorId == null) {
            String sql = base + " ORDER BY created_at DESC, target_id DESC LIMIT " + limit;
            return jdbc.query(sql, MAPPER, authorPrincipalId, userId);
        }
        String sql = base + " WHERE (created_at < ? OR (created_at = ? AND target_id < ?)) " +
                "ORDER BY created_at DESC, target_id DESC LIMIT " + limit;
        return jdbc.query(sql, MAPPER, authorPrincipalId, userId, cursorTs, cursorTs, cursorId);
    }

    public static class ViolationRow {
        public String targetType;
        public long targetId;
        public String reason;
        public String status;
        public OffsetDateTime createdAt;
    }
}
