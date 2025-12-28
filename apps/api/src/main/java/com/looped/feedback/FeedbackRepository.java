package com.looped.feedback;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class FeedbackRepository {
    private final JdbcTemplate jdbc;

    public FeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Long userId, String email, String title, String message) {
        Long id = jdbc.query(
                "INSERT INTO feedback(user_id, email, title, message) VALUES (?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId, email, title, message
        );
        if (id == null) {
            throw new IllegalStateException("Failed to insert feedback");
        }
        return id;
    }

    public List<Row> listForAdmin(String status, OffsetDateTime from, OffsetDateTime to,
                                  OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = "SELECT f.*, u.handle AS user_handle FROM feedback f " +
                "LEFT JOIN users u ON u.id = f.user_id ";
        StringBuilder sql = new StringBuilder(base);
        List<Object> params = new ArrayList<>();
        boolean hasWhere = false;

        if (status != null && !status.isBlank()) {
            sql.append("WHERE f.status = ? ");
            params.add(status);
            hasWhere = true;
        }
        if (from != null) {
            sql.append(hasWhere ? "AND " : "WHERE ");
            sql.append("f.created_at >= ? ");
            params.add(from);
            hasWhere = true;
        }
        if (to != null) {
            sql.append(hasWhere ? "AND " : "WHERE ");
            sql.append("f.created_at < ? ");
            params.add(to);
            hasWhere = true;
        }
        if (cursorTs != null && cursorId != null) {
            sql.append(hasWhere ? "AND " : "WHERE ");
            sql.append("(f.created_at < ? OR (f.created_at = ? AND f.id < ?)) ");
            params.add(cursorTs);
            params.add(cursorTs);
            params.add(cursorId);
        }
        sql.append("ORDER BY f.created_at DESC, f.id DESC LIMIT ? ");
        params.add(limit);

        return jdbc.query(sql.toString(), params.toArray(), MAPPER);
    }

    private static final RowMapper<Row> MAPPER = new RowMapper<>() {
        @Override
        public Row mapRow(ResultSet rs, int rowNum) throws SQLException {
            Row row = new Row();
            row.id = rs.getLong("id");
            long userId = rs.getLong("user_id");
            row.userId = rs.wasNull() ? null : userId;
            row.userHandle = rs.getString("user_handle");
            row.email = rs.getString("email");
            row.title = rs.getString("title");
            row.message = rs.getString("message");
            row.status = rs.getString("status");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.reviewedAt = rs.getObject("reviewed_at", OffsetDateTime.class);
            long reviewedBy = rs.getLong("reviewed_by");
            row.reviewedBy = rs.wasNull() ? null : reviewedBy;
            row.reviewedNote = rs.getString("reviewed_note");
            return row;
        }
    };

    public static class Row {
        public long id;
        public Long userId;
        public String userHandle;
        public String email;
        public String title;
        public String message;
        public String status;
        public OffsetDateTime createdAt;
        public OffsetDateTime reviewedAt;
        public Long reviewedBy;
        public String reviewedNote;
    }
}
