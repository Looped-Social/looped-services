package com.looped.comments;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class CommentsRepository {
    private final JdbcTemplate jdbc;

    public CommentsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CommentRow> MAPPER = new RowMapper<>() {
        @Override
        public CommentRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            CommentRow row = new CommentRow();
            row.id = rs.getLong("id");
            row.postId = rs.getLong("post_id");
            row.userId = rs.getLong("user_id");
            row.companyId = rs.getLong("company_id");
            row.content = rs.getString("content");
            long parent = rs.getLong("parent_id");
            row.parentId = rs.wasNull() ? null : parent;
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public List<CommentRow> findByUser(long userId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT id, post_id, user_id, company_id, content, parent_id, created_at " +
                            "FROM comments WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, userId, limit
            );
        }
        return jdbc.query(
                "SELECT id, post_id, user_id, company_id, content, parent_id, created_at " +
                        "FROM comments WHERE user_id = ? AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, userId, cursorTs, cursorTs, cursorId, limit
        );
    }

    public static class CommentRow {
        public long id;
        public long postId;
        public long userId;
        public long companyId;
        public String content;
        public Long parentId;
        public OffsetDateTime createdAt;
    }
}
