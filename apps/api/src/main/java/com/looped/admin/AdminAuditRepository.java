package com.looped.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class AdminAuditRepository {
    private final JdbcTemplate jdbc;

    public AdminAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Row> MAPPER = new RowMapper<>() {
        @Override
        public Row mapRow(ResultSet rs, int rowNum) throws SQLException {
            Row row = new Row();
            row.id = rs.getLong("id");
            long actorId = rs.getLong("actor_admin_id");
            row.actorAdminId = rs.wasNull() ? null : actorId;
            row.action = rs.getString("action");
            row.targetType = rs.getString("target_type");
            long targetId = rs.getLong("target_id");
            row.targetId = rs.wasNull() ? null : targetId;
            row.meta = rs.getString("meta");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.actorEmail = rs.getString("actor_email");
            return row;
        }
    };

    public void log(Long actorAdminId, String action, String targetType, Long targetId, String meta) {
        jdbc.update(
                "INSERT INTO admin_audit_log(actor_admin_id, action, target_type, target_id, meta) VALUES (?,?,?,?,?)",
                actorAdminId, action, targetType, targetId, meta
        );
    }

    public List<Row> list(OffsetDateTime cursorTs, Long cursorId, int limit) {
        String select = "SELECT aal.id, aal.actor_admin_id, aal.action, aal.target_type, aal.target_id, aal.meta, aal.created_at, " +
                "au.email AS actor_email " +
                "FROM admin_audit_log aal " +
                "LEFT JOIN admin_users au ON au.id = aal.actor_admin_id ";
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    select + "ORDER BY aal.created_at DESC, aal.id DESC LIMIT ?",
                    MAPPER, limit
            );
        }
        return jdbc.query(
                select +
                        "WHERE (aal.created_at < ? OR (aal.created_at = ? AND aal.id < ?)) " +
                        "ORDER BY aal.created_at DESC, aal.id DESC LIMIT ?",
                MAPPER, cursorTs, cursorTs, cursorId, limit
        );
    }

    public static class Row {
        public long id;
        public Long actorAdminId;
        public String action;
        public String targetType;
        public Long targetId;
        public String meta;
        public OffsetDateTime createdAt;
        public String actorEmail;
    }
}
