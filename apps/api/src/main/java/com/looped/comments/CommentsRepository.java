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
        long media = rs.getLong("media_asset_id");
        row.mediaAssetId = rs.wasNull() ? null : media;
        long parent = rs.getLong("parent_id");
        row.parentId = rs.wasNull() ? null : parent;
        row.likesCount = rs.getInt("likes_count");
        row.replyCount = rs.getInt("reply_count");
        row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
        row.deletedAt = rs.getObject("deleted_at", OffsetDateTime.class);
        row.visibility = rs.getString("visibility");
        row.quarantinedAt = rs.getObject("quarantined_at", OffsetDateTime.class);
        row.quarantineReason = rs.getString("quarantine_reason");
        row.removedAt = rs.getObject("removed_at", OffsetDateTime.class);
        long removedBy = rs.getLong("removed_by");
        row.removedBy = rs.wasNull() ? null : removedBy;
        row.removedReason = rs.getString("removed_reason");
        return row;
    }

    public List<CommentRow> findByUser(long userId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                "SELECT id, post_id, user_id, author_principal_id, company_id, content, media_asset_id, parent_id, likes_count, reply_count, created_at, deleted_at, " +
                        "visibility, quarantined_at, quarantine_reason, removed_at, removed_by, removed_reason " +
                        "FROM comments WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, userId, limit
        );
        }
        return jdbc.query(
                "SELECT id, post_id, user_id, author_principal_id, company_id, content, media_asset_id, parent_id, likes_count, reply_count, created_at, deleted_at, " +
                        "visibility, quarantined_at, quarantine_reason, removed_at, removed_by, removed_reason " +
                        "FROM comments WHERE user_id = ? AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                MAPPER, userId, cursorTs, cursorTs, cursorId, limit
        );
    }

    public Optional<CommentRow> findById(long id) {
        var list = jdbc.query(
                "SELECT id, post_id, user_id, author_principal_id, company_id, content, media_asset_id, parent_id, likes_count, reply_count, created_at, deleted_at, " +
                        "visibility, quarantined_at, quarantine_reason, removed_at, removed_by, removed_reason " +
                        "FROM comments WHERE id = ?",
                MAPPER, id
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<Long> findCommentIdsByMediaAsset(long mediaAssetId) {
        return jdbc.queryForList(
                "SELECT id FROM comments WHERE deleted_at IS NULL AND removed_at IS NULL AND media_asset_id = ?",
                Long.class,
                mediaAssetId
        );
    }

    public List<CommentRow> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query(
                "SELECT id, post_id, user_id, author_principal_id, company_id, content, media_asset_id, parent_id, likes_count, reply_count, created_at, deleted_at, " +
                        "visibility, quarantined_at, quarantine_reason, removed_at, removed_by, removed_reason " +
                        "FROM comments WHERE id IN (" + placeholders + ")",
                MAPPER,
                ids.toArray()
        );
    }

    public CommentRow insert(long postId, Long userId, long authorPrincipalId, long companyId, String content, Long mediaAssetId, Long parentId) {
        Long id = jdbc.query(
                "INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, media_asset_id, parent_id) VALUES (?,?,?,?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                postId, userId, authorPrincipalId, companyId, content, mediaAssetId, parentId
        );
        return findById(id).orElseThrow();
    }

    public List<CommentViewRow> findByPost(long postId, long viewerPrincipalId, Long postAuthorPrincipalId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String sql = """
                SELECT c.id, c.post_id, c.user_id, c.author_principal_id, c.company_id, c.content, c.media_asset_id, c.parent_id, c.likes_count, c.reply_count, c.created_at, c.deleted_at,
                       c.visibility, c.quarantined_at, c.quarantine_reason, c.removed_at, c.removed_by, c.removed_reason,
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
                AND (c.visibility = 'public' OR c.author_principal_id = ?)
                """;
        Object[] params;
        if (cursorTs == null || cursorId == null) {
            sql += "ORDER BY c.created_at ASC, c.id ASC LIMIT ?";
            params = new Object[]{viewerPrincipalId, postAuthorPrincipalId, postId, viewerPrincipalId, limit};
        } else {
            sql += "AND (c.created_at > ? OR (c.created_at = ? AND c.id > ?)) ORDER BY c.created_at ASC, c.id ASC LIMIT ?";
            params = new Object[]{viewerPrincipalId, postAuthorPrincipalId, postId, viewerPrincipalId, cursorTs, cursorTs, cursorId, limit};
        }
        return jdbc.query(sql, this::mapViewRow, params);
    }

    public List<CommentViewRow> findReplies(long postId, long parentCommentId, long viewerPrincipalId, Long postAuthorPrincipalId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String sql = """
                SELECT c.id, c.post_id, c.user_id, c.author_principal_id, c.company_id, c.content, c.media_asset_id, c.parent_id, c.likes_count, c.reply_count, c.created_at, c.deleted_at,
                       c.visibility, c.quarantined_at, c.quarantine_reason, c.removed_at, c.removed_by, c.removed_reason,
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
                AND (c.visibility = 'public' OR c.author_principal_id = ?)
                """;
        Object[] params;
        if (cursorTs == null || cursorId == null) {
            sql += "ORDER BY c.created_at ASC, c.id ASC LIMIT ?";
            params = new Object[]{viewerPrincipalId, postAuthorPrincipalId, parentCommentId, postId, viewerPrincipalId, limit};
        } else {
            sql += "AND (c.created_at > ? OR (c.created_at = ? AND c.id > ?)) ORDER BY c.created_at ASC, c.id ASC LIMIT ?";
            params = new Object[]{viewerPrincipalId, postAuthorPrincipalId, parentCommentId, postId, viewerPrincipalId, cursorTs, cursorTs, cursorId, limit};
        }
        return jdbc.query(sql, this::mapViewRow, params);
    }

    public Optional<CommentViewRow> findViewById(long id, long viewerPrincipalId, Long postAuthorPrincipalId) {
        var list = jdbc.query(
                """
	                        SELECT c.id, c.post_id, c.user_id, c.author_principal_id, c.company_id, c.content, c.media_asset_id, c.parent_id, c.likes_count, c.reply_count, c.created_at, c.deleted_at,
	                               c.visibility, c.quarantined_at, c.quarantine_reason, c.removed_at, c.removed_by, c.removed_reason,
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
	                SELECT c.id, c.post_id, c.user_id, c.author_principal_id, c.company_id, c.content, c.media_asset_id, c.parent_id, c.likes_count, c.reply_count, c.created_at, c.deleted_at,
	                       c.visibility, c.quarantined_at, c.quarantine_reason, c.removed_at, c.removed_by, c.removed_reason,
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

    public List<CommentViewRow> findByAuthorPrincipalWithView(long targetPrincipalId, long viewerPrincipalId,
                                                              OffsetDateTime cursorTs, Long cursorId, int limit) {
        String sql = """
	                SELECT c.id, c.post_id, c.user_id, c.author_principal_id, c.company_id, c.content, c.media_asset_id, c.parent_id, c.likes_count, c.reply_count, c.created_at, c.deleted_at,
	                       c.visibility, c.quarantined_at, c.quarantine_reason, c.removed_at, c.removed_by, c.removed_reason,
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
                WHERE c.author_principal_id = ? AND (pr.kind = 'anon' OR u.id IS NOT NULL)
                """;
        Object[] params;
        if (cursorTs == null || cursorId == null) {
            sql += "ORDER BY c.created_at DESC, c.id DESC LIMIT ?";
            params = new Object[]{viewerPrincipalId, targetPrincipalId, limit};
        } else {
            sql += "AND (c.created_at < ? OR (c.created_at = ? AND c.id < ?)) ORDER BY c.created_at DESC, c.id DESC LIMIT ?";
            params = new Object[]{viewerPrincipalId, targetPrincipalId, cursorTs, cursorTs, cursorId, limit};
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

    public boolean deleteLikeIfPresent(long commentId, long principalId) {
        int rows = jdbc.update(
                "DELETE FROM comment_likes WHERE comment_id = ? AND liker_principal_id = ?",
                commentId, principalId
        );
        return rows > 0;
    }

    public void incrementCommentLikes(long commentId) {
        jdbc.update("UPDATE comments SET likes_count = likes_count + 1 WHERE id = ?", commentId);
    }

    public void decrementCommentLikes(long commentId) {
        jdbc.update("UPDATE comments SET likes_count = GREATEST(likes_count - 1, 0) WHERE id = ?", commentId);
    }

    public void incrementReplyCount(long commentId) {
        jdbc.update("UPDATE comments SET reply_count = reply_count + 1 WHERE id = ?", commentId);
    }

    public void decrementReplyCount(long commentId) {
        jdbc.update("UPDATE comments SET reply_count = GREATEST(reply_count - 1, 0) WHERE id = ?", commentId);
    }

    public boolean updateContent(long commentId, String content) {
        return jdbc.update(
                "UPDATE comments SET content = ? WHERE id = ? AND deleted_at IS NULL AND removed_at IS NULL",
                content, commentId
        ) > 0;
    }

    public boolean softDelete(long commentId) {
        return jdbc.update(
                "UPDATE comments SET deleted_at = now(), content = '' WHERE id = ? AND deleted_at IS NULL AND removed_at IS NULL",
                commentId
        ) > 0;
    }

    public boolean quarantine(long commentId, String reason) {
        int rows = jdbc.update(
                "UPDATE comments SET visibility = 'quarantined', quarantined_at = now(), quarantine_reason = ? " +
                        "WHERE id = ? AND removed_at IS NULL AND visibility <> 'quarantined'",
                reason, commentId
        );
        return rows > 0;
    }

    public boolean unquarantine(long commentId) {
        int rows = jdbc.update(
                "UPDATE comments SET visibility = 'public', quarantined_at = NULL, quarantine_reason = NULL " +
                        "WHERE id = ? AND removed_at IS NULL AND visibility <> 'public'",
                commentId
        );
        return rows > 0;
    }

    public boolean removeByAdmin(long commentId, long adminId, String reason) {
        int rows = jdbc.update(
                "UPDATE comments SET removed_at = now(), removed_by = ?, removed_reason = ?, content = '' " +
                        "WHERE id = ? AND removed_at IS NULL",
                adminId, reason, commentId
        );
        return rows > 0;
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
        public Long mediaAssetId;
        public Long parentId;
        public int likesCount;
        public int replyCount;
        public OffsetDateTime createdAt;
        public OffsetDateTime deletedAt;
        public String visibility;
        public OffsetDateTime quarantinedAt;
        public String quarantineReason;
        public OffsetDateTime removedAt;
        public Long removedBy;
        public String removedReason;
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
