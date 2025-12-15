package com.looped.posts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class SavedPostsRepository {
    private final JdbcTemplate jdbc;

    public SavedPostsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfAbsent(long userId, long postId) {
        int rows = jdbc.update(
                "INSERT INTO saved_posts(user_id, post_id) VALUES (?,?) ON CONFLICT (user_id, post_id) DO NOTHING",
                userId, postId
        );
        return rows > 0;
    }

    public boolean delete(long userId, long postId) {
        int rows = jdbc.update("DELETE FROM saved_posts WHERE user_id=? AND post_id=?", userId, postId);
        return rows > 0;
    }

    public List<SavedPostRow> findSavedPosts(long userId, long companyId, OffsetDateTime cursorTs, Long cursorPostId, int limit) {
        if (cursorTs == null || cursorPostId == null) {
            return jdbc.query(BASE_QUERY + " WHERE s.user_id=? AND p.company_id=? ORDER BY s.created_at DESC, p.id DESC LIMIT ?",
                    MAPPER, userId, companyId, limit);
        } else {
            return jdbc.query(BASE_QUERY +
                            " WHERE s.user_id=? AND p.company_id=? AND (s.created_at < ? OR (s.created_at = ? AND p.id < ?)) " +
                            "ORDER BY s.created_at DESC, p.id DESC LIMIT ?",
                    MAPPER, userId, companyId, cursorTs, cursorTs, cursorPostId, limit);
        }
    }

    private static final String BASE_QUERY = "SELECT " +
            "p.id AS post_id, p.author_id, p.company_id, p.content, p.media_asset_id, p.likes_count, p.comments_count, p.share_count, p.created_at AS post_created_at, " +
            "s.created_at AS saved_created_at " +
            "FROM saved_posts s JOIN posts p ON p.id = s.post_id";

    private static final RowMapper<SavedPostRow> MAPPER = new RowMapper<>() {
        @Override
        public SavedPostRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            PostRepository.PostRow post = new PostRepository.PostRow();
            post.id = rs.getLong("post_id");
            post.authorId = rs.getLong("author_id");
            post.companyId = rs.getLong("company_id");
            post.content = rs.getString("content");
            long media = rs.getLong("media_asset_id");
            post.mediaAssetId = rs.wasNull() ? null : media;
            post.likesCount = rs.getInt("likes_count");
            post.commentsCount = rs.getInt("comments_count");
            post.shareCount = rs.getInt("share_count");
            post.createdAt = rs.getObject("post_created_at", OffsetDateTime.class);

            SavedPostRow row = new SavedPostRow();
            row.post = post;
            row.savedAt = rs.getObject("saved_created_at", OffsetDateTime.class);
            return row;
        }
    };

    public static class SavedPostRow {
        public PostRepository.PostRow post;
        public OffsetDateTime savedAt;
    }
}
