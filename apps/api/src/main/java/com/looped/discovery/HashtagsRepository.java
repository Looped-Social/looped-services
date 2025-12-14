package com.looped.discovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class HashtagsRepository {
    private final JdbcTemplate jdbc;

    public HashtagsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<HashtagRow> MAPPER = new RowMapper<>() {
        @Override
        public HashtagRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            HashtagRow row = new HashtagRow();
            row.id = rs.getLong("id");
            row.companyId = rs.getLong("company_id");
            row.name = rs.getString("name");
            row.usageCount = rs.getInt("usage_count");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public List<HashtagRow> search(long companyId, String query, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String like = "%" + query.toLowerCase() + "%";
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT id, company_id, name, usage_count, created_at " +
                            "FROM hashtags WHERE company_id = ? AND LOWER(name) LIKE ? " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, companyId, like, limit
            );
        }
        return jdbc.query(
                "SELECT id, company_id, name, usage_count, created_at " +
                        "FROM hashtags WHERE company_id = ? AND LOWER(name) LIKE ? " +
                        "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, companyId, like, cursorTs, cursorTs, cursorId, limit
        );
    }

    public static class HashtagRow {
        public long id;
        public long companyId;
        public String name;
        public int usageCount;
        public OffsetDateTime createdAt;
    }
}
