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

    public boolean insertIfAbsent(long principalId, long postId) {
        int rows = jdbc.update(
                "INSERT INTO post_likes(liker_principal_id, post_id) VALUES (?,?) ON CONFLICT (liker_principal_id, post_id) DO NOTHING",
                principalId, postId
        );
        return rows > 0;
    }

    public void incrementPostLikes(long postId) {
        jdbc.update("UPDATE posts SET likes_count = likes_count + 1 WHERE id=?", postId);
    }

    public List<LikedPostRow> findLikedPosts(long principalId, OffsetDateTime cursorTs, Long cursorPostId, int limit) {
        if (cursorTs == null || cursorPostId == null) {
            return jdbc.query(BASE_QUERY + " WHERE l.liker_principal_id=? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) " +
                            "ORDER BY l.created_at ASC, p.id ASC LIMIT ?",
                    MAPPER, principalId, limit);
        }
        return jdbc.query(BASE_QUERY +
                        " WHERE l.liker_principal_id=? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) " +
                        "AND (l.created_at > ? OR (l.created_at = ? AND p.id > ?)) " +
                        "ORDER BY l.created_at ASC, p.id ASC LIMIT ?",
                MAPPER, principalId, cursorTs, cursorTs, cursorPostId, limit);
    }

    private static final String BASE_QUERY = "SELECT " +
            "p.id AS post_id, p.author_id, p.author_principal_id, p.is_anon, p.anon_profile_id, p.anon_company_id, " +
            "p.company_id, p.community_id, p.content, p.media_asset_id, p.likes_count, p.comments_count, p.share_count, " +
            "p.created_at AS post_created_at, " +
            "COALESCE(u.handle, ap.handle) AS author_handle, " +
            "u.display_name AS author_display_name, " +
            "u.profile_image_url AS author_profile_image_url, " +
            "CASE WHEN p.is_anon THEN true ELSE COALESCE(u.is_anonymous, false) END AS author_is_anonymous, " +
            "l.created_at AS liked_created_at " +
            "FROM post_likes l JOIN posts p ON p.id = l.post_id " +
            "LEFT JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL " +
            "LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id";

    private static final RowMapper<LikedPostRow> MAPPER = new RowMapper<>() {
        @Override
        public LikedPostRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            PostRepository.PostRow post = new PostRepository.PostRow();
            post.id = rs.getLong("post_id");
            long authorId = rs.getLong("author_id");
            post.authorId = rs.wasNull() ? null : authorId;
            post.authorPrincipalId = rs.getLong("author_principal_id");
            post.isAnon = rs.getBoolean("is_anon");
            long anonProfile = rs.getLong("anon_profile_id");
            post.anonProfileId = rs.wasNull() ? null : anonProfile;
            long anonCompany = rs.getLong("anon_company_id");
            post.anonCompanyId = rs.wasNull() ? null : anonCompany;
            post.companyId = rs.getLong("company_id");
            long community = rs.getLong("community_id");
            post.communityId = rs.wasNull() ? null : community;
            post.content = rs.getString("content");
            long media = rs.getLong("media_asset_id");
            post.mediaAssetId = rs.wasNull() ? null : media;
            post.likesCount = rs.getInt("likes_count");
            post.commentsCount = rs.getInt("comments_count");
            post.shareCount = rs.getInt("share_count");
            post.createdAt = rs.getObject("post_created_at", OffsetDateTime.class);
            post.authorHandle = rs.getString("author_handle");
            post.authorDisplayName = rs.getString("author_display_name");
            post.authorProfileImageUrl = rs.getString("author_profile_image_url");
            post.authorIsAnonymous = rs.getBoolean("author_is_anonymous");

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
