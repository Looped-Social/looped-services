package com.looped.posts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
public class RepostsRepository {
    private final JdbcTemplate jdbc;

    public RepostsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfAbsent(long reposterPrincipalId, long postId) {
        int rows = jdbc.update(
                "INSERT INTO post_reposts(reposter_principal_id, post_id) VALUES (?, ?) " +
                        "ON CONFLICT (reposter_principal_id, post_id) DO NOTHING",
                reposterPrincipalId, postId
        );
        return rows > 0;
    }

    public boolean deleteIfPresent(long reposterPrincipalId, long postId) {
        int rows = jdbc.update(
                "DELETE FROM post_reposts WHERE reposter_principal_id = ? AND post_id = ?",
                reposterPrincipalId, postId
        );
        return rows > 0;
    }

    public int incrementPostReposts(long postId) {
        Integer count = jdbc.query(
                "UPDATE posts SET repost_count = repost_count + 1 WHERE id = ? RETURNING repost_count",
                rs -> rs.next() ? rs.getInt(1) : null,
                postId
        );
        return count == null ? 0 : count;
    }

    public int decrementPostReposts(long postId) {
        Integer count = jdbc.query(
                "UPDATE posts SET repost_count = GREATEST(repost_count - 1, 0) WHERE id = ? RETURNING repost_count",
                rs -> rs.next() ? rs.getInt(1) : null,
                postId
        );
        return count == null ? 0 : count;
    }

    public int repostCount(long postId) {
        Integer count = jdbc.queryForObject("SELECT repost_count FROM posts WHERE id = ?", Integer.class, postId);
        return count == null ? 0 : count;
    }

    public Set<Long> findRepostedPostIds(long principalId, List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(postIds.size(), "?"));
        Object[] params = new Object[postIds.size() + 1];
        params[0] = principalId;
        for (int i = 0; i < postIds.size(); i++) {
            params[i + 1] = postIds.get(i);
        }
        List<Long> rows = jdbc.queryForList(
                "SELECT post_id FROM post_reposts WHERE reposter_principal_id = ? AND post_id IN (" + placeholders + ")",
                Long.class,
                params
        );
        return new HashSet<>(rows);
    }

    public List<FollowedRepostRow> followedRepostsForPosts(long viewerPrincipalId, List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(postIds.size(), "?"));
        Object[] params = new Object[postIds.size() + 2];
        params[0] = viewerPrincipalId;
        params[1] = viewerPrincipalId;
        for (int i = 0; i < postIds.size(); i++) {
            params[i + 2] = postIds.get(i);
        }
        String sql = """
                SELECT post_id, user_id, username, total_count
                FROM (
                    SELECT r.post_id,
                           u.id AS user_id,
                           u.handle AS username,
                           COUNT(*) OVER (PARTITION BY r.post_id) AS total_count,
                           ROW_NUMBER() OVER (PARTITION BY r.post_id ORDER BY r.created_at DESC, r.id DESC) AS rn
                    FROM post_reposts r
                    JOIN principal_follows f
                      ON f.followee_principal_id = r.reposter_principal_id
                     AND f.follower_principal_id = ?
                    JOIN principals p ON p.id = r.reposter_principal_id AND p.kind = 'user'
                    JOIN users u ON u.id = p.user_id AND u.deleted_at IS NULL
                    LEFT JOIN principal_blocks pb
                      ON pb.blocker_principal_id = ?
                     AND pb.blocked_principal_id = r.reposter_principal_id
                    WHERE pb.blocked_principal_id IS NULL
                      AND r.post_id IN (""" + placeholders + """
                      )
                ) s
                WHERE rn <= 2
                ORDER BY post_id ASC, rn ASC
                """;
        return jdbc.query(
                sql,
                (rs, rowNum) -> new FollowedRepostRow(
                        rs.getLong("post_id"),
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getInt("total_count")
                ),
                params
        );
    }

    public record FollowedRepostRow(long postId, long userId, String username, int totalCount) {}

    public List<RepostedPostRow> repostedPosts(long reposterPrincipalId, OffsetDateTime cursorTs, Long cursorId, int limit,
                                              long viewerUserId, boolean hideAnonymousPosts) {
        String hideAnonymousFilter = hideAnonymousPosts
                ? "AND (NOT (p.is_anon OR COALESCE(u.is_anonymous, false)) OR p.author_id = ?) "
                : "";
        Object[] params;
        String sqlBase = "SELECT r.id AS repost_id, r.created_at AS repost_created_at, " +
                "p.id AS post_id, p.author_id, p.author_principal_id, p.is_anon, p.anon_profile_id, p.anon_company_id, " +
                "p.company_id, p.community_id, c.name AS community_name, c.kind AS community_kind, " +
                "p.content, p.media_asset_id, p.likes_count, p.comments_count, p.share_count, p.repost_count, " +
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
                "CASE WHEN p.is_anon THEN true ELSE COALESCE(u.is_anonymous, false) END AS author_is_anonymous " +
                "FROM post_reposts r " +
                "JOIN posts p ON p.id = r.post_id " +
                "LEFT JOIN communities c ON c.id = p.community_id " +
                "LEFT JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL " +
                "LEFT JOIN community_verifications cv ON cv.user_id = u.id AND cv.community_id = u.display_community_id " +
                "AND cv.verified = true AND (cv.expires_at IS NULL OR cv.expires_at > now()) " +
                "LEFT JOIN communities dc ON dc.id = cv.community_id " +
                "LEFT JOIN communities ds ON ds.id = u.display_specialization_id " +
                "AND ds.kind = 'specialization' AND ds.specialization_type IN ('major','department') " +
                "LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id " +
                "WHERE r.reposter_principal_id = ? " +
                "AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) " +
                hideAnonymousFilter;

        if (cursorTs == null || cursorId == null) {
            sqlBase += "ORDER BY r.created_at DESC, r.id DESC LIMIT ?";
            params = hideAnonymousPosts
                    ? new Object[]{reposterPrincipalId, viewerUserId, limit}
                    : new Object[]{reposterPrincipalId, limit};
        } else {
            sqlBase += "AND (r.created_at < ? OR (r.created_at = ? AND r.id < ?)) ORDER BY r.created_at DESC, r.id DESC LIMIT ?";
            params = hideAnonymousPosts
                    ? new Object[]{reposterPrincipalId, viewerUserId, cursorTs, cursorTs, cursorId, limit}
                    : new Object[]{reposterPrincipalId, cursorTs, cursorTs, cursorId, limit};
        }

        return jdbc.query(sqlBase, (rs, rowNum) -> {
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

            return new RepostedPostRow(
                    rs.getLong("repost_id"),
                    rs.getObject("repost_created_at", OffsetDateTime.class),
                    post
            );
        });
    }

    public record RepostedPostRow(long repostId, OffsetDateTime repostedAt, PostRepository.PostRow post) {}
}

