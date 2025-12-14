package com.looped.discovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class LoopsRepository {
    private final JdbcTemplate jdbc;

    public LoopsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LoopRow> MAPPER = new RowMapper<>() {
        @Override
        public LoopRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            LoopRow row = new LoopRow();
            row.id = rs.getLong("id");
            row.companyId = rs.getLong("company_id");
            row.name = rs.getString("name");
            row.description = rs.getString("description");
            row.memberCount = rs.getInt("member_count");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public List<LoopRow> search(long companyId, String query, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String like = "%" + query.toLowerCase() + "%";
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT id, company_id, name, description, member_count, created_at " +
                            "FROM loops WHERE company_id = ? AND (LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ?) " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, companyId, like, like, limit
            );
        }
        return jdbc.query(
                "SELECT id, company_id, name, description, member_count, created_at " +
                        "FROM loops WHERE company_id = ? AND (LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ?) " +
                        "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, companyId, like, like, cursorTs, cursorTs, cursorId, limit
        );
    }

    public static class LoopRow {
        public long id;
        public long companyId;
        public String name;
        public String description;
        public int memberCount;
        public OffsetDateTime createdAt;
    }
}
