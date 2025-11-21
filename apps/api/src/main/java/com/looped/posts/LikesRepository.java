package com.looped.posts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class LikesRepository {
    private final JdbcTemplate jdbc;

    public LikesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfAbsent(long userId, long postId) {
        int rows = jdbc.update(
                "INSERT INTO likes(user_id, post_id) VALUES (?,?) ON CONFLICT (user_id, post_id) DO NOTHING",
                userId, postId
        );
        return rows > 0;
    }

    public void incrementPostLikes(long postId) {
        jdbc.update("UPDATE posts SET likes_count = likes_count + 1 WHERE id=?", postId);
    }

    public List<LikedPostRow> findLikedPosts(long userId, long companyId, OffsetDateTime cursorTs, Long cursorPostId, int limit) {
        if (cursorTs == null || cursorPostId == null) {
            return jdbc.query(BASE_QUERY + " WHERE l.user_id=? AND p.company_id=? ORDER BY l.created_at ASC, p.id ASC LIMIT ?",
                    MAPPER, userId, companyId, limit);
        } else {
            return jdbc.query(BASE_QUERY +
                            " WHERE l.user_id=? AND p.company_id=? AND (l.created_at > ? OR (l.created_at = ? AND p.id > ?)) " +
                            "ORDER BY l.created_at ASC, p.id ASC LIMIT ?",
                    MAPPER, userId, companyId, cursorTs, cursorTs, cursorPostId, limit);
        }
    }

    private static final String BASE_QUERY = "SELECT " +
            "p.id AS post_id, p.author_id, p.company_id, p.content, p.media_asset_id, p.likes_count, p.comments_count, p.share_count, p.created_at AS post_created_at, " +
            "l.created_at AS liked_created_at " +
            "FROM likes l JOIN posts p ON p.id = l.post_id";

    private static final RowMapper<LikedPostRow> MAPPER = new RowMapper<>() {
        @Override
        public LikedPostRow mapRow(ResultSet rs, int rowNum) throws SQLException {
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

            LikedPostRow row = new LikedPostRow();
            row.post = post;
            row.likedAt = rs.getObject("liked_created_at", OffsetDateTime.class);
            return row;
        }
    };

    public static class LikedPostRow {
        public PostRepository.PostRow post;
        public OffsetDateTime likedAt;
    }
}
