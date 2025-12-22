package com.looped.posts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class PostRepository {
    private final JdbcTemplate jdbc;

    public PostRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String BASE_SELECT =
            "SELECT p.id, p.author_id, p.author_principal_id, p.is_anon, p.anon_profile_id, p.anon_company_id, " +
            "p.company_id, p.community_id, p.content, p.media_asset_id, p.likes_count, p.comments_count, p.share_count, p.created_at, " +
            "p.removed_at, p.removed_by, p.removed_reason, " +
            "COALESCE(u.handle, ap.handle) AS author_handle, " +
            "u.display_name AS author_display_name, " +
            "u.profile_image_url AS author_profile_image_url, " +
            "CASE WHEN p.is_anon THEN true ELSE COALESCE(u.is_anonymous, false) END AS author_is_anonymous " +
            "FROM posts p " +
            "LEFT JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL " +
            "LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id ";

    private static final RowMapper<PostRow> MAPPER = new RowMapper<>() {
        @Override
        public PostRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            PostRow p = new PostRow();
            p.id = rs.getLong("id");
            long authorId = rs.getLong("author_id");
            p.authorId = rs.wasNull() ? null : authorId;
            p.authorPrincipalId = rs.getLong("author_principal_id");
            p.isAnon = rs.getBoolean("is_anon");
            long anonProfile = rs.getLong("anon_profile_id");
            p.anonProfileId = rs.wasNull() ? null : anonProfile;
            long anonCompany = rs.getLong("anon_company_id");
            p.anonCompanyId = rs.wasNull() ? null : anonCompany;
            p.companyId = rs.getLong("company_id");
            long community = rs.getLong("community_id");
            p.communityId = rs.wasNull() ? null : community;
            p.content = rs.getString("content");
            long media = rs.getLong("media_asset_id");
            p.mediaAssetId = rs.wasNull() ? null : media;
            p.likesCount = rs.getInt("likes_count");
            p.commentsCount = rs.getInt("comments_count");
            p.shareCount = rs.getInt("share_count");
            p.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            p.removedAt = rs.getObject("removed_at", OffsetDateTime.class);
            long removedBy = rs.getLong("removed_by");
            p.removedBy = rs.wasNull() ? null : removedBy;
            p.removedReason = rs.getString("removed_reason");
            p.authorHandle = rs.getString("author_handle");
            p.authorDisplayName = rs.getString("author_display_name");
            p.authorProfileImageUrl = rs.getString("author_profile_image_url");
            p.authorIsAnonymous = rs.getBoolean("author_is_anonymous");
            return p;
        }
    };

    public PostRow insert(Long authorId, long authorPrincipalId, long companyId, Long communityId, String content, Long mediaAssetId,
                          boolean isAnon, Long anonProfileId, Long anonCompanyId, byte[] anonCert, String anonCertKid,
                          byte[] anonSig, byte[] anonEphemeralPubkey) {
        Long id = jdbc.query(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, media_asset_id, is_anon, " +
                        "anon_profile_id, anon_company_id, anon_cert, anon_cert_kid, anon_sig, anon_ephemeral_pubkey) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                authorId, authorPrincipalId, companyId, communityId, content, mediaAssetId, isAnon,
                anonProfileId, anonCompanyId, anonCert, anonCertKid, anonSig, anonEphemeralPubkey
        );
        return findById(id).orElseThrow();
    }

    public Optional<PostRow> findById(Long id) {
        var list = jdbc.query(
                BASE_SELECT + "WHERE p.id = ? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL)",
                MAPPER, id
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<PostRow> findByIdIncludingRemoved(Long id) {
        var list = jdbc.query(
                BASE_SELECT + "WHERE p.id = ? AND (p.author_id IS NULL OR u.id IS NOT NULL)",
                MAPPER, id
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public java.util.List<PostRow> findFeedByCommunity(long communityId, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    BASE_SELECT + "WHERE p.community_id=? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) " +
                            "ORDER BY p.created_at ASC, p.id ASC LIMIT ?",
                    MAPPER, communityId, limit
            );
        }
        return jdbc.query(
                BASE_SELECT + "WHERE p.community_id=? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) " +
                        "AND (p.created_at > ? OR (p.created_at = ? AND p.id > ?)) " +
                        "ORDER BY p.created_at ASC, p.id ASC LIMIT ?",
                MAPPER, communityId, cursorTs, cursorTs, cursorId, limit
        );
    }

    public java.util.List<PostRow> findPopular(java.time.OffsetDateTime asOf, java.time.OffsetDateTime since, Long cursorScore, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        String scoreExpr = "((p.likes_count * 2 + p.comments_count + p.share_count) * 1000 - " +
                "FLOOR(EXTRACT(EPOCH FROM (?::timestamptz - p.created_at)) / 3600))";
        String base = "SELECT p.id, p.author_id, p.author_principal_id, p.is_anon, p.anon_profile_id, p.anon_company_id, " +
                "p.company_id, p.community_id, p.content, p.media_asset_id, p.likes_count, p.comments_count, p.share_count, p.created_at, " +
                "COALESCE(u.handle, ap.handle) AS author_handle, u.display_name AS author_display_name, " +
                "u.profile_image_url AS author_profile_image_url, " +
                "CASE WHEN p.is_anon THEN true ELSE COALESCE(u.is_anonymous, false) END AS author_is_anonymous, " +
                scoreExpr + " AS score FROM posts p " +
                "LEFT JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL " +
                "LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id " +
                "WHERE p.created_at >= ? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL)";
        if (cursorScore == null || cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT id, author_id, author_principal_id, is_anon, anon_profile_id, anon_company_id, company_id, community_id, " +
                            "content, media_asset_id, likes_count, comments_count, share_count, created_at, " +
                            "author_handle, author_display_name, author_profile_image_url, author_is_anonymous " +
                            "FROM (" + base + ") s ORDER BY score DESC, created_at DESC, id DESC LIMIT ?",
                    MAPPER, asOf, since, limit
            );
        }
        return jdbc.query(
                "SELECT id, author_id, author_principal_id, is_anon, anon_profile_id, anon_company_id, company_id, community_id, " +
                        "content, media_asset_id, likes_count, comments_count, share_count, created_at, " +
                        "author_handle, author_display_name, author_profile_image_url, author_is_anonymous " +
                        "FROM (" + base + ") s WHERE (score < ? OR (score = ? AND (created_at < ? OR (created_at = ? AND id < ?)))) " +
                        "ORDER BY score DESC, created_at DESC, id DESC LIMIT ?",
                MAPPER, asOf, since, cursorScore, cursorScore, cursorTs, cursorTs, cursorId, limit
        );
    }

    public java.util.List<PostRow> findByAuthor(long authorId, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    BASE_SELECT + "WHERE p.author_id=? AND p.removed_at IS NULL AND u.id IS NOT NULL " +
                            "ORDER BY p.created_at ASC, p.id ASC LIMIT ?",
                    MAPPER, authorId, limit
            );
        } else {
            return jdbc.query(
                    BASE_SELECT + "WHERE p.author_id=? AND p.removed_at IS NULL AND u.id IS NOT NULL " +
                            "AND (p.created_at > ? OR (p.created_at = ? AND p.id > ?)) " +
                            "ORDER BY p.created_at ASC, p.id ASC LIMIT ?",
                    MAPPER, authorId, cursorTs, cursorTs, cursorId, limit
            );
        }
    }

    public java.util.List<PostRow> findByAuthorPrincipal(long authorPrincipalId, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    BASE_SELECT + "WHERE p.author_principal_id=? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) " +
                            "ORDER BY p.created_at ASC, p.id ASC LIMIT ?",
                    MAPPER, authorPrincipalId, limit
            );
        } else {
            return jdbc.query(
                    BASE_SELECT + "WHERE p.author_principal_id=? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) " +
                            "AND (p.created_at > ? OR (p.created_at = ? AND p.id > ?)) " +
                        "ORDER BY p.created_at ASC, p.id ASC LIMIT ?",
                MAPPER, authorPrincipalId, cursorTs, cursorTs, cursorId, limit
            );
        }
    }

    public java.util.List<PostRow> findByHashtag(long companyId, String name, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = BASE_SELECT +
                "JOIN hashtag_posts hp ON hp.post_id = p.id " +
                "JOIN hashtags h ON h.id = hp.hashtag_id " +
                "WHERE h.company_id = ? AND h.name = ? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) ";
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    base + "ORDER BY p.created_at ASC, p.id ASC LIMIT ?",
                    MAPPER, companyId, name, limit
            );
        }
        return jdbc.query(
                base + "AND (p.created_at > ? OR (p.created_at = ? AND p.id > ?)) " +
                        "ORDER BY p.created_at ASC, p.id ASC LIMIT ?",
                MAPPER, companyId, name, cursorTs, cursorTs, cursorId, limit
        );
    }

    public java.util.List<TrendingRow> findTrendingWithMedia(java.time.OffsetDateTime asOf, java.time.OffsetDateTime since, Long communityId, int limit) {
        String scoreExpr = "((p.likes_count * 2 + p.comments_count + p.share_count) * 1000 - " +
                "FLOOR(EXTRACT(EPOCH FROM (?::timestamptz - p.created_at)) / 3600))";
        String base = "SELECT p.id, p.author_id, p.author_principal_id, p.is_anon, p.anon_profile_id, p.anon_company_id, " +
                "p.company_id, p.community_id, p.content, p.media_asset_id, p.likes_count, p.comments_count, p.share_count, p.created_at, " +
                "COALESCE(u.handle, ap.handle) AS author_handle, u.display_name AS author_display_name, " +
                "u.profile_image_url AS author_profile_image_url, " +
                "CASE WHEN p.is_anon THEN true ELSE COALESCE(u.is_anonymous, false) END AS author_is_anonymous, " +
                "c.name AS community_name, c.kind AS community_kind, " +
                scoreExpr + " AS score FROM posts p " +
                "JOIN communities c ON c.id = p.community_id " +
                "LEFT JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL " +
                "LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id " +
                "WHERE p.media_asset_id IS NOT NULL AND p.created_at >= ? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) ";
        if (communityId != null) {
            base += "AND p.community_id = ? ";
        }
        base += "ORDER BY score DESC, p.created_at DESC, p.id DESC LIMIT ?";

        Object[] params;
        if (communityId != null) {
            params = new Object[]{asOf, since, communityId, limit};
        } else {
            params = new Object[]{asOf, since, limit};
        }
        return jdbc.query(base, TRENDING_MAPPER, params);
    }

    public void incrementCommentsCount(long postId) {
        jdbc.update("UPDATE posts SET comments_count = comments_count + 1 WHERE id = ?", postId);
    }

    public boolean remove(long postId, Long adminId, String reason) {
        int rows = jdbc.update(
                "UPDATE posts SET removed_at = now(), removed_by = ?, removed_reason = ? " +
                        "WHERE id = ? AND removed_at IS NULL",
                adminId, reason, postId
        );
        return rows > 0;
    }

    public boolean restore(long postId) {
        int rows = jdbc.update(
                "UPDATE posts SET removed_at = NULL, removed_by = NULL, removed_reason = NULL WHERE id = ?",
                postId
        );
        return rows > 0;
    }

    public static class PostRow {
        public long id;
        public Long authorId;
        public long authorPrincipalId;
        public boolean isAnon;
        public Long anonProfileId;
        public Long anonCompanyId;
        public long companyId;
        public Long communityId;
        public String content;
        public Long mediaAssetId;
        public int likesCount;
        public int commentsCount;
        public int shareCount;
        public OffsetDateTime createdAt;
        public OffsetDateTime removedAt;
        public Long removedBy;
        public String removedReason;
        public String authorHandle;
        public String authorDisplayName;
        public String authorProfileImageUrl;
        public boolean authorIsAnonymous;
    }

    public static class TrendingRow extends PostRow {
        public String communityName;
        public String communityKind;
    }

    private static final RowMapper<TrendingRow> TRENDING_MAPPER = new RowMapper<>() {
        @Override
        public TrendingRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            TrendingRow p = new TrendingRow();
            p.id = rs.getLong("id");
            long authorId = rs.getLong("author_id");
            p.authorId = rs.wasNull() ? null : authorId;
            p.authorPrincipalId = rs.getLong("author_principal_id");
            p.isAnon = rs.getBoolean("is_anon");
            long anonProfile = rs.getLong("anon_profile_id");
            p.anonProfileId = rs.wasNull() ? null : anonProfile;
            long anonCompany = rs.getLong("anon_company_id");
            p.anonCompanyId = rs.wasNull() ? null : anonCompany;
            p.companyId = rs.getLong("company_id");
            long community = rs.getLong("community_id");
            p.communityId = rs.wasNull() ? null : community;
            p.content = rs.getString("content");
            long media = rs.getLong("media_asset_id");
            p.mediaAssetId = rs.wasNull() ? null : media;
            p.likesCount = rs.getInt("likes_count");
            p.commentsCount = rs.getInt("comments_count");
            p.shareCount = rs.getInt("share_count");
            p.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            p.authorHandle = rs.getString("author_handle");
            p.authorDisplayName = rs.getString("author_display_name");
            p.authorProfileImageUrl = rs.getString("author_profile_image_url");
            p.authorIsAnonymous = rs.getBoolean("author_is_anonymous");
            p.communityName = rs.getString("community_name");
            p.communityKind = rs.getString("community_kind");
            return p;
        }
    };
}
