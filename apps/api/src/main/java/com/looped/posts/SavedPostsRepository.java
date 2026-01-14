package com.looped.posts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
public class SavedPostsRepository {
    private final JdbcTemplate jdbc;

    public SavedPostsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String hideAnonymousFilter(boolean hideAnonymousPosts) {
        if (!hideAnonymousPosts) return "";
        return "AND (NOT (p.is_anon OR COALESCE(u.is_anonymous, false)) OR p.author_id = ?) ";
    }

    public boolean insertIfAbsent(long principalId, long postId) {
        int rows = jdbc.update(
                "INSERT INTO principal_saved_posts(saver_principal_id, post_id) VALUES (?,?) ON CONFLICT (saver_principal_id, post_id) DO NOTHING",
                principalId, postId
        );
        return rows > 0;
    }

    public boolean delete(long principalId, long postId) {
        int rows = jdbc.update("DELETE FROM principal_saved_posts WHERE saver_principal_id=? AND post_id=?", principalId, postId);
        return rows > 0;
    }

    public List<SavedPostRow> findSavedPosts(long principalId, OffsetDateTime cursorTs, Long cursorPostId, int limit,
                                             long viewerUserId, boolean hideAnonymousPosts) {
        String filter = hideAnonymousFilter(hideAnonymousPosts);
        if (cursorTs == null || cursorPostId == null) {
            return jdbc.query(BASE_QUERY + " WHERE s.saver_principal_id=? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) " +
                            filter +
                            "ORDER BY s.created_at DESC, p.id DESC LIMIT ?",
                    MAPPER,
                    hideAnonymousPosts ? new Object[]{principalId, viewerUserId, limit} : new Object[]{principalId, limit});
        }
        return jdbc.query(BASE_QUERY +
                        " WHERE s.saver_principal_id=? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) " +
                        filter +
                        "AND (s.created_at < ? OR (s.created_at = ? AND p.id < ?)) " +
                        "ORDER BY s.created_at DESC, p.id DESC LIMIT ?",
                MAPPER,
                hideAnonymousPosts
                        ? new Object[]{principalId, viewerUserId, cursorTs, cursorTs, cursorPostId, limit}
                        : new Object[]{principalId, cursorTs, cursorTs, cursorPostId, limit});
    }

    public Set<Long> findSavedPostIds(long principalId, List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(postIds.size(), "?"));
        Object[] params = new Object[postIds.size() + 1];
        params[0] = principalId;
        for (int i = 0; i < postIds.size(); i++) {
            params[i + 1] = postIds.get(i);
        }
        List<Long> rows = jdbc.queryForList(
                "SELECT post_id FROM principal_saved_posts WHERE saver_principal_id = ? AND post_id IN (" + placeholders + ")",
                Long.class,
                params
        );
        return new HashSet<>(rows);
    }

    private static final String BASE_QUERY = "SELECT " +
            "p.id AS post_id, p.author_id, p.author_principal_id, p.is_anon, p.anon_profile_id, p.anon_company_id, " +
            "p.company_id, p.community_id, c.name AS community_name, c.kind AS community_kind, " +
            "p.content, p.media_asset_id, " +
            "COALESCE(pm.media_asset_ids, CASE WHEN p.media_asset_id IS NULL THEN NULL ELSE ARRAY[p.media_asset_id] END) AS media_asset_ids, " +
            "p.likes_count, p.comments_count, p.share_count, p.repost_count, " +
            "p.created_at AS post_created_at, " +
            "COALESCE(u.handle, ap.handle) AS author_handle, " +
            "u.display_name AS author_display_name, " +
            "u.first_name AS author_first_name, " +
            "u.last_name AS author_last_name, " +
            "u.profile_image_url AS author_profile_image_url, " +
            "dc.id AS author_display_community_id, " +
            "dc.name AS author_display_community_name, " +
            "dc.kind AS author_display_community_kind, " +
            "dc.specialization_type AS author_display_community_specialization_type, " +
            "ds.id AS author_display_specialization_id, " +
            "ds.name AS author_display_specialization_name, " +
            "ds.kind AS author_display_specialization_kind, " +
            "ds.specialization_type AS author_display_specialization_type, " +
            "CASE WHEN p.is_anon THEN true ELSE COALESCE(u.is_anonymous, false) END AS author_is_anonymous, " +
            "s.created_at AS saved_created_at " +
            "FROM principal_saved_posts s JOIN posts p ON p.id = s.post_id " +
            "LEFT JOIN communities c ON c.id = p.community_id " +
            "LEFT JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL " +
            "LEFT JOIN community_verifications cv ON cv.user_id = u.id AND cv.community_id = u.display_community_id " +
            "AND cv.verified = true AND (cv.expires_at IS NULL OR cv.expires_at > now()) " +
            "LEFT JOIN communities dc ON dc.id = cv.community_id " +
            "LEFT JOIN communities ds ON ds.id = u.display_specialization_id " +
            "AND ds.kind = 'specialization' AND ds.specialization_type IN ('major','department') " +
            "LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id " +
            "LEFT JOIN LATERAL (" +
            "  SELECT ARRAY_AGG(pma.media_asset_id ORDER BY pma.sort_order) AS media_asset_ids " +
            "  FROM post_media_assets pma WHERE pma.post_id = p.id" +
            ") pm ON true";

    private static final RowMapper<SavedPostRow> MAPPER = new RowMapper<>() {
        @Override
        public SavedPostRow mapRow(ResultSet rs, int rowNum) throws SQLException {
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
            post.communityName = rs.getString("community_name");
            post.communityKind = rs.getString("community_kind");
            post.content = rs.getString("content");
            long media = rs.getLong("media_asset_id");
            post.mediaAssetId = rs.wasNull() ? null : media;
            post.mediaAssetIds = PostRepository.readMediaAssetIds(rs);
            post.likesCount = rs.getInt("likes_count");
            post.commentsCount = rs.getInt("comments_count");
            post.shareCount = rs.getInt("share_count");
            post.repostCount = rs.getInt("repost_count");
            post.createdAt = rs.getObject("post_created_at", OffsetDateTime.class);
            post.authorHandle = rs.getString("author_handle");
            post.authorDisplayName = rs.getString("author_display_name");
            post.authorFirstName = rs.getString("author_first_name");
            post.authorLastName = rs.getString("author_last_name");
            post.authorProfileImageUrl = rs.getString("author_profile_image_url");
            long displayCommunityId = rs.getLong("author_display_community_id");
            post.authorDisplayCommunityId = rs.wasNull() ? null : displayCommunityId;
            post.authorDisplayCommunityName = rs.getString("author_display_community_name");
            post.authorDisplayCommunityKind = rs.getString("author_display_community_kind");
            post.authorDisplayCommunitySpecializationType = rs.getString("author_display_community_specialization_type");
            long displaySpecializationId = rs.getLong("author_display_specialization_id");
            post.authorDisplaySpecializationId = rs.wasNull() ? null : displaySpecializationId;
            post.authorDisplaySpecializationName = rs.getString("author_display_specialization_name");
            post.authorDisplaySpecializationKind = rs.getString("author_display_specialization_kind");
            post.authorDisplaySpecializationType = rs.getString("author_display_specialization_type");
            post.authorIsAnonymous = rs.getBoolean("author_is_anonymous");

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
