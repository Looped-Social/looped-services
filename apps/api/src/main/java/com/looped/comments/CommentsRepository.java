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
        long userId = rs.getLong("user_id");
        row.userId = rs.wasNull() ? null : userId;
        row.authorPrincipalId = rs.getLong("author_principal_id");
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
                "SELECT id, post_id, user_id, author_principal_id, company_id, content, parent_id, likes_count, created_at " +
                        "FROM comments WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, userId, limit
        );
        }
        return jdbc.query(
                "SELECT id, post_id, user_id, author_principal_id, company_id, content, parent_id, likes_count, created_at " +
                        "FROM comments WHERE user_id = ? AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, userId, cursorTs, cursorTs, cursorId, limit
        );
    }

    public Optional<CommentRow> findById(long id) {
        var list = jdbc.query(
                "SELECT id, post_id, user_id, author_principal_id, company_id, content, parent_id, likes_count, created_at FROM comments WHERE id = ?",
                MAPPER, id
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public CommentRow insert(long postId, Long userId, long authorPrincipalId, long companyId, String content, Long parentId) {
        Long id = jdbc.query(
                "INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, parent_id) VALUES (?,?,?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                postId, userId, authorPrincipalId, companyId, content, parentId
        );
        return findById(id).orElseThrow();
    }

    public List<CommentViewRow> findByPost(long postId, long viewerPrincipalId, Long postAuthorPrincipalId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String sql = """
                SELECT c.id, c.post_id, c.user_id, c.author_principal_id, c.company_id, c.content, c.parent_id, c.likes_count, c.created_at,
                       p.kind AS author_kind, p.user_id AS author_user_id, p.anon_profile_id AS author_anon_profile_id,
                       COALESCE(u.handle, ap.handle) AS author_handle,
                       u.display_name AS author_display_name, u.profile_image_url AS author_profile_image_url,
                       COALESCE(u.company_id, ap.company_id) AS author_company_id,
                       CASE WHEN p.kind = 'anon' THEN true ELSE COALESCE(u.is_anonymous, false) END AS author_is_anonymous,
                       CASE WHEN cv.liker_principal_id IS NULL THEN false ELSE true END AS viewer_liked,
                       CASE WHEN cc.liker_principal_id IS NULL THEN false ELSE true END AS liked_by_creator
                FROM comments c
                JOIN principals p ON p.id = c.author_principal_id
                LEFT JOIN users u ON u.id = p.user_id AND u.deleted_at IS NULL
                LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id
                LEFT JOIN comment_likes cv ON cv.comment_id = c.id AND cv.liker_principal_id = ?
                LEFT JOIN comment_likes cc ON cc.comment_id = c.id AND cc.liker_principal_id = ?
                WHERE c.post_id = ?
                AND (p.kind = 'anon' OR u.id IS NOT NULL)
                """;
        Object[] params;
        if (cursorTs == null || cursorId == null) {
            sql += "ORDER BY c.created_at ASC, c.id ASC LIMIT ?";
            params = new Object[]{viewerPrincipalId, postAuthorPrincipalId, postId, limit};
        } else {
            sql += "AND (c.created_at > ? OR (c.created_at = ? AND c.id > ?)) ORDER BY c.created_at ASC, c.id ASC LIMIT ?";
            params = new Object[]{viewerPrincipalId, postAuthorPrincipalId, postId, cursorTs, cursorTs, cursorId, limit};
        }
        return jdbc.query(sql, this::mapViewRow, params);
    }

    public List<CommentViewRow> findReplies(long postId, long parentCommentId, long viewerPrincipalId, Long postAuthorPrincipalId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String sql = """
                SELECT c.id, c.post_id, c.user_id, c.author_principal_id, c.company_id, c.content, c.parent_id, c.likes_count, c.created_at,
                       p.kind AS author_kind, p.user_id AS author_user_id, p.anon_profile_id AS author_anon_profile_id,
                       COALESCE(u.handle, ap.handle) AS author_handle,
                       u.display_name AS author_display_name, u.profile_image_url AS author_profile_image_url,
                       COALESCE(u.company_id, ap.company_id) AS author_company_id,
                       CASE WHEN p.kind = 'anon' THEN true ELSE COALESCE(u.is_anonymous, false) END AS author_is_anonymous,
                       CASE WHEN cv.liker_principal_id IS NULL THEN false ELSE true END AS viewer_liked,
                       CASE WHEN cc.liker_principal_id IS NULL THEN false ELSE true END AS liked_by_creator
                FROM comments c
                JOIN principals p ON p.id = c.author_principal_id
                LEFT JOIN users u ON u.id = p.user_id AND u.deleted_at IS NULL
                LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id
                LEFT JOIN comment_likes cv ON cv.comment_id = c.id AND cv.liker_principal_id = ?
                LEFT JOIN comment_likes cc ON cc.comment_id = c.id AND cc.liker_principal_id = ?
                WHERE c.parent_id = ? AND c.post_id = ?
                AND (p.kind = 'anon' OR u.id IS NOT NULL)
                """;
        Object[] params;
        if (cursorTs == null || cursorId == null) {
            sql += "ORDER BY c.created_at ASC, c.id ASC LIMIT ?";
            params = new Object[]{viewerPrincipalId, postAuthorPrincipalId, parentCommentId, postId, limit};
        } else {
            sql += "AND (c.created_at > ? OR (c.created_at = ? AND c.id > ?)) ORDER BY c.created_at ASC, c.id ASC LIMIT ?";
            params = new Object[]{viewerPrincipalId, postAuthorPrincipalId, parentCommentId, postId, cursorTs, cursorTs, cursorId, limit};
        }
        return jdbc.query(sql, this::mapViewRow, params);
    }

    public Optional<CommentViewRow> findViewById(long id, long viewerPrincipalId, Long postAuthorPrincipalId) {
        var list = jdbc.query(
                """
                        SELECT c.id, c.post_id, c.user_id, c.author_principal_id, c.company_id, c.content, c.parent_id, c.likes_count, c.created_at,
                               p.kind AS author_kind, p.user_id AS author_user_id, p.anon_profile_id AS author_anon_profile_id,
                               COALESCE(u.handle, ap.handle) AS author_handle,
                               u.display_name AS author_display_name, u.profile_image_url AS author_profile_image_url,
                               COALESCE(u.company_id, ap.company_id) AS author_company_id,
                               CASE WHEN p.kind = 'anon' THEN true ELSE COALESCE(u.is_anonymous, false) END AS author_is_anonymous,
                               CASE WHEN cv.liker_principal_id IS NULL THEN false ELSE true END AS viewer_liked,
                               CASE WHEN cc.liker_principal_id IS NULL THEN false ELSE true END AS liked_by_creator
                        FROM comments c
                        JOIN principals p ON p.id = c.author_principal_id
                        LEFT JOIN users u ON u.id = p.user_id AND u.deleted_at IS NULL
                        LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id
                        LEFT JOIN comment_likes cv ON cv.comment_id = c.id AND cv.liker_principal_id = ?
                        LEFT JOIN comment_likes cc ON cc.comment_id = c.id AND cc.liker_principal_id = ?
                        WHERE c.id = ? AND (p.kind = 'anon' OR u.id IS NOT NULL)
                        """,
                this::mapViewRow,
                viewerPrincipalId, postAuthorPrincipalId, id
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<CommentViewRow> findByUserWithView(long targetUserId, long viewerPrincipalId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String sql = """
                SELECT c.id, c.post_id, c.user_id, c.author_principal_id, c.company_id, c.content, c.parent_id, c.likes_count, c.created_at,
                       pr.kind AS author_kind, pr.user_id AS author_user_id, pr.anon_profile_id AS author_anon_profile_id,
                       COALESCE(u.handle, ap.handle) AS author_handle,
                       u.display_name AS author_display_name, u.profile_image_url AS author_profile_image_url,
                       COALESCE(u.company_id, ap.company_id) AS author_company_id,
                       CASE WHEN pr.kind = 'anon' THEN true ELSE COALESCE(u.is_anonymous, false) END AS author_is_anonymous,
                       CASE WHEN cv.liker_principal_id IS NULL THEN false ELSE true END AS viewer_liked,
                       CASE WHEN cc.liker_principal_id IS NULL THEN false ELSE true END AS liked_by_creator
                FROM comments c
                JOIN principals pr ON pr.id = c.author_principal_id
                LEFT JOIN users u ON u.id = pr.user_id AND u.deleted_at IS NULL
                LEFT JOIN anonymous_profiles ap ON ap.id = pr.anon_profile_id
                JOIN posts p ON p.id = c.post_id AND p.removed_at IS NULL
                LEFT JOIN comment_likes cv ON cv.comment_id = c.id AND cv.liker_principal_id = ?
                LEFT JOIN comment_likes cc ON cc.comment_id = c.id AND cc.liker_principal_id = p.author_principal_id
                WHERE c.user_id = ? AND (pr.kind = 'anon' OR u.id IS NOT NULL)
                """;
        Object[] params;
        if (cursorTs == null || cursorId == null) {
            sql += "ORDER BY c.created_at DESC, c.id DESC LIMIT ?";
            params = new Object[]{viewerPrincipalId, targetUserId, limit};
        } else {
            sql += "AND (c.created_at < ? OR (c.created_at = ? AND c.id < ?)) ORDER BY c.created_at DESC, c.id DESC LIMIT ?";
            params = new Object[]{viewerPrincipalId, targetUserId, cursorTs, cursorTs, cursorId, limit};
        }
        return jdbc.query(sql, this::mapViewRow, params);
    }

    public boolean insertLikeIfAbsent(long commentId, long principalId, Long userId) {
        int rows = jdbc.update(
                "INSERT INTO comment_likes(comment_id, liker_principal_id, user_id) VALUES (?,?,?) " +
                        "ON CONFLICT (comment_id, liker_principal_id) DO NOTHING",
                commentId, principalId, userId
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
        author.principalId = rs.getLong("author_principal_id");
        long authorUserId = rs.getLong("author_user_id");
        author.userId = rs.wasNull() ? null : authorUserId;
        long anonProfileId = rs.getLong("author_anon_profile_id");
        author.anonProfileId = rs.wasNull() ? null : anonProfileId;
        author.handle = rs.getString("author_handle");
        author.displayName = rs.getString("author_display_name");
        long company = rs.getLong("author_company_id");
        author.companyId = rs.wasNull() ? null : company;
        author.isAnonymous = rs.getBoolean("author_is_anonymous");
        author.profileImageUrl = rs.getString("author_profile_image_url");
        row.author = author;

        row.viewerLiked = rs.getBoolean("viewer_liked");
        row.likedByCreator = rs.getBoolean("liked_by_creator");
        return row;
    }

    public static class CommentRow {
        public long id;
        public long postId;
        public Long userId;
        public long authorPrincipalId;
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
        public long principalId;
        public Long userId;
        public Long anonProfileId;
        public String handle;
        public String displayName;
        public Long companyId;
        public boolean isAnonymous;
        public String profileImageUrl;
    }
}
