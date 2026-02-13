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
public class AdminAnnouncementsRepository {
    private final JdbcTemplate jdbc;

    public AdminAnnouncementsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Row> MAPPER = new RowMapper<>() {
        @Override
        public Row mapRow(ResultSet rs, int rowNum) throws SQLException {
            Row row = new Row();
            row.id = rs.getLong("id");
            long actorAdminId = rs.getLong("actor_admin_id");
            row.actorAdminId = rs.wasNull() ? null : actorAdminId;
            row.scope = rs.getString("scope");
            long companyId = rs.getLong("company_id");
            row.companyId = rs.wasNull() ? null : companyId;
            row.companyName = rs.getString("company_name");
            row.title = rs.getString("title");
            row.body = rs.getString("body");
            row.deeplink = rs.getString("deeplink");
            row.sentCount = rs.getInt("sent_count");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public long insert(Long actorAdminId,
                       String scope,
                       Long companyId,
                       String title,
                       String body,
                       String deeplink,
                       int sentCount) {
        Long id = jdbc.query(
                "INSERT INTO admin_announcements(actor_admin_id, scope, company_id, title, body, deeplink, sent_count) " +
                        "VALUES (?,?,?,?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                actorAdminId,
                scope,
                companyId,
                title,
                body,
                deeplink,
                Math.max(sentCount, 0)
        );
        return id == null ? 0L : id;
    }

    public List<Row> list(String scope, Long companyId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id,
                       a.actor_admin_id,
                       a.scope,
                       a.company_id,
                       c.name AS company_name,
                       a.title,
                       a.body,
                       a.deeplink,
                       a.sent_count,
                       a.created_at
                FROM admin_announcements a
                LEFT JOIN companies c ON c.id = a.company_id
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();

        if ("company".equals(scope)) {
            sql.append(" AND a.scope = 'company' ");
        } else if ("global".equals(scope)) {
            sql.append(" AND a.scope = 'global' ");
        }
        if (companyId != null) {
            sql.append(" AND a.company_id = ? ");
            args.add(companyId);
        }
        if (cursorTs != null && cursorId != null) {
            sql.append(" AND (a.created_at < ? OR (a.created_at = ? AND a.id < ?)) ");
            args.add(cursorTs);
            args.add(cursorTs);
            args.add(cursorId);
        }

        sql.append(" ORDER BY a.created_at DESC, a.id DESC LIMIT ? ");
        args.add(Math.max(1, Math.min(limit, 200)));

        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public static class Row {
        public long id;
        public Long actorAdminId;
        public String scope;
        public Long companyId;
        public String companyName;
        public String title;
        public String body;
        public String deeplink;
        public int sentCount;
        public OffsetDateTime createdAt;
    }
}
