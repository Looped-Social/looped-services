package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class CommunitiesRepository {
    private final JdbcTemplate jdbc;

    public CommunitiesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CommunityRow> MAPPER = new RowMapper<>() {
        @Override
        public CommunityRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            CommunityRow row = new CommunityRow();
            row.id = rs.getLong("id");
            row.kind = rs.getString("kind");
            row.name = rs.getString("name");
            row.description = rs.getString("description");
            row.memberCount = rs.getInt("member_count");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public List<CommunityRow> search(String query, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String like = "%" + query.toLowerCase() + "%";
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT id, kind, name, description, member_count, created_at " +
                            "FROM communities WHERE LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ? " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, like, like, limit
            );
        }
        return jdbc.query(
                "SELECT id, kind, name, description, member_count, created_at " +
                        "FROM communities WHERE (LOWER(name) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ?) " +
                        "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, like, like, cursorTs, cursorTs, cursorId, limit
        );
    }

    public Optional<CommunityRow> findById(long id) {
        var list = jdbc.query(
                "SELECT id, kind, name, description, member_count, created_at FROM communities WHERE id = ?",
                MAPPER, id
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public static class CommunityRow {
        public long id;
        public String kind;
        public String name;
        public String description;
        public int memberCount;
        public OffsetDateTime createdAt;
    }
}
