package com.looped.comments;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class CommentsRepository {
    private final JdbcTemplate jdbc;

    public CommentsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CommentRow> MAPPER = new RowMapper<>() {
        @Override
        public CommentRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return mapComment(rs);
        }
    };

    private static CommentRow mapComment(ResultSet rs) throws SQLException {
        CommentRow row = new CommentRow();
        row.id = rs.getLong("id");
        row.postId = rs.getLong("post_id");
        row.userId = rs.getLong("user_id");
        row.companyId = rs.getLong("company_id");
        row.content = rs.getString("content");
        long parent = rs.getLong("parent_id");
        row.parentId = rs.wasNull() ? null : parent;
        row.likesCount = rs.getInt("likes_count");
        row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
        return row;
    }

    public List<CommentRow> findByUser(long userId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT id, post_id, user_id, company_id, content, parent_id, likes_count, created_at " +
                            "FROM comments WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                    MAPPER, userId, limit
            );
        }
        return jdbc.query(
                "SELECT id, post_id, user_id, company_id, content, parent_id, likes_count, created_at " +
                        "FROM comments WHERE user_id = ? AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, userId, cursorTs, cursorTs, cursorId, limit
        );
    }

    public Optional<CommentRow> findById(long id) {
        var list = jdbc.query(
                "SELECT id, post_id, user_id, company_id, content, parent_id, likes_count, created_at FROM comments WHERE id = ?",
                MAPPER, id
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public CommentRow insert(long postId, long userId, long companyId, String content, Long parentId) {
        Long id = jdbc.query(
                "INSERT INTO comments(post_id, user_id, company_id, content, parent_id) VALUES (?,?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                postId, userId, companyId, content, parentId
        );
        return findById(id).orElseThrow();
    }

    public List<CommentViewRow> findByPost(long postId, long viewerId, long postAuthorId, long companyId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String sql = """
                SELECT c.id, c.post_id, c.user_id, c.company_id, c.content, c.parent_id, c.likes_count, c.created_at,
                       u.handle, u.display_name, u.is_anonymous, u.company_id AS user_company_id, u.profile_image_url,
                       CASE WHEN cv.user_id IS NULL THEN false ELSE true END AS viewer_liked,
                       CASE WHEN cc.user_id IS NULL THEN false ELSE true END AS liked_by_creator
                FROM comments c
                JOIN users u ON u.id = c.user_id
                LEFT JOIN comment_likes cv ON cv.comment_id = c.id AND cv.user_id = ?
                LEFT JOIN comment_likes cc ON cc.comment_id = c.id AND cc.user_id = ?
                WHERE c.post_id = ? AND c.company_id = ?
                """;
        Object[] params;
        if (cursorTs == null || cursorId == null) {
            sql += "ORDER BY c.created_at ASC, c.id ASC LIMIT ?";
            params = new Object[]{viewerId, postAuthorId, postId, companyId, limit};
        } else {
            sql += "AND (c.created_at > ? OR (c.created_at = ? AND c.id > ?)) ORDER BY c.created_at ASC, c.id ASC LIMIT ?";
            params = new Object[]{viewerId, postAuthorId, postId, companyId, cursorTs, cursorTs, cursorId, limit};
        }
        return jdbc.query(sql, this::mapViewRow, params);
    }

    public List<CommentViewRow> findReplies(long postId, long parentCommentId, long viewerId, long postAuthorId, long companyId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String sql = """
                SELECT c.id, c.post_id, c.user_id, c.company_id, c.content, c.parent_id, c.likes_count, c.created_at,
                       u.handle, u.display_name, u.is_anonymous, u.company_id AS user_company_id, u.profile_image_url,
                       CASE WHEN cv.user_id IS NULL THEN false ELSE true END AS viewer_liked,
                       CASE WHEN cc.user_id IS NULL THEN false ELSE true END AS liked_by_creator
                FROM comments c
                JOIN users u ON u.id = c.user_id
                LEFT JOIN comment_likes cv ON cv.comment_id = c.id AND cv.user_id = ?
                LEFT JOIN comment_likes cc ON cc.comment_id = c.id AND cc.user_id = ?
                WHERE c.parent_id = ? AND c.post_id = ? AND c.company_id = ?
                """;
        Object[] params;
        if (cursorTs == null || cursorId == null) {
            sql += "ORDER BY c.created_at ASC, c.id ASC LIMIT ?";
            params = new Object[]{viewerId, postAuthorId, parentCommentId, postId, companyId, limit};
        } else {
            sql += "AND (c.created_at > ? OR (c.created_at = ? AND c.id > ?)) ORDER BY c.created_at ASC, c.id ASC LIMIT ?";
            params = new Object[]{viewerId, postAuthorId, parentCommentId, postId, companyId, cursorTs, cursorTs, cursorId, limit};
        }
        return jdbc.query(sql, this::mapViewRow, params);
    }

    public Optional<CommentViewRow> findViewById(long id, long viewerId, long postAuthorId, long companyId) {
        var list = jdbc.query(
                """
                        SELECT c.id, c.post_id, c.user_id, c.company_id, c.content, c.parent_id, c.likes_count, c.created_at,
                               u.handle, u.display_name, u.is_anonymous, u.company_id AS user_company_id, u.profile_image_url,
                               CASE WHEN cv.user_id IS NULL THEN false ELSE true END AS viewer_liked,
                               CASE WHEN cc.user_id IS NULL THEN false ELSE true END AS liked_by_creator
                        FROM comments c
                        JOIN users u ON u.id = c.user_id
                        LEFT JOIN comment_likes cv ON cv.comment_id = c.id AND cv.user_id = ?
                        LEFT JOIN comment_likes cc ON cc.comment_id = c.id AND cc.user_id = ?
                        WHERE c.id = ? AND c.company_id = ?
                        """,
                this::mapViewRow,
                viewerId, postAuthorId, id, companyId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public boolean insertLikeIfAbsent(long commentId, long userId) {
        int rows = jdbc.update(
                "INSERT INTO comment_likes(comment_id, user_id) VALUES (?,?) ON CONFLICT (comment_id, user_id) DO NOTHING",
                commentId, userId
        );
        return rows > 0;
    }

    public void incrementCommentLikes(long commentId) {
        jdbc.update("UPDATE comments SET likes_count = likes_count + 1 WHERE id = ?", commentId);
    }

    private CommentViewRow mapViewRow(ResultSet rs, int rowNum) throws SQLException {
        CommentViewRow row = new CommentViewRow();
        row.comment = mapComment(rs);

        AuthorRow author = new AuthorRow();
        author.id = rs.getLong("user_id");
        author.handle = rs.getString("handle");
        author.displayName = rs.getString("display_name");
        long company = rs.getLong("user_company_id");
        author.companyId = rs.wasNull() ? null : company;
        author.isAnonymous = rs.getBoolean("is_anonymous");
        author.profileImageUrl = rs.getString("profile_image_url");
        row.author = author;

        row.viewerLiked = rs.getBoolean("viewer_liked");
        row.likedByCreator = rs.getBoolean("liked_by_creator");
        return row;
    }

    public static class CommentRow {
        public long id;
        public long postId;
        public long userId;
        public long companyId;
        public String content;
        public Long parentId;
        public int likesCount;
        public OffsetDateTime createdAt;
    }

    public static class CommentViewRow {
        public CommentRow comment;
        public AuthorRow author;
        public boolean viewerLiked;
        public boolean likedByCreator;
    }

    public static class AuthorRow {
        public long id;
        public String handle;
        public String displayName;
        public Long companyId;
        public boolean isAnonymous;
        public String profileImageUrl;
    }
}
