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

    private static String hideAnonymousFilter(boolean hideAnonymousPosts) {
        if (!hideAnonymousPosts) return "";
        return "AND (NOT (p.is_anon OR COALESCE(u.is_anonymous, false)) OR p.author_id = ?) ";
    }

    private static String blocksFilter() {
        return "AND NOT EXISTS (" +
                "SELECT 1 FROM principal_blocks pb " +
                "WHERE (pb.blocker_principal_id = ? AND pb.blocked_principal_id = p.author_principal_id) " +
                "OR (pb.blocker_principal_id = p.author_principal_id AND pb.blocked_principal_id = ?)" +
                ") ";
    }

    private static final String BASE_SELECT =
            "SELECT p.id, p.author_id, p.author_principal_id, p.is_anon, p.anon_profile_id, p.anon_company_id, " +
            "p.company_id, p.community_id, c.name AS community_name, c.kind AS community_kind, " +
            "p.content, p.media_asset_id, p.likes_count, p.comments_count, p.share_count, p.repost_count, p.created_at, " +
            "p.removed_at, p.removed_by, p.removed_reason, " +
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
            "FROM posts p " +
            "LEFT JOIN communities c ON c.id = p.community_id " +
            "LEFT JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL " +
            "LEFT JOIN community_verifications cv ON cv.user_id = u.id AND cv.community_id = u.display_community_id " +
            "AND cv.verified = true AND (cv.expires_at IS NULL OR cv.expires_at > now()) " +
            "LEFT JOIN communities dc ON dc.id = cv.community_id " +
            "LEFT JOIN communities ds ON ds.id = u.display_specialization_id " +
            "AND ds.kind = 'specialization' AND ds.specialization_type IN ('major','department') " +
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
            p.communityName = rs.getString("community_name");
            p.communityKind = rs.getString("community_kind");
            p.content = rs.getString("content");
            long media = rs.getLong("media_asset_id");
            p.mediaAssetId = rs.wasNull() ? null : media;
            p.likesCount = rs.getInt("likes_count");
            p.commentsCount = rs.getInt("comments_count");
            p.shareCount = rs.getInt("share_count");
            p.repostCount = rs.getInt("repost_count");
            p.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            p.removedAt = rs.getObject("removed_at", OffsetDateTime.class);
            long removedBy = rs.getLong("removed_by");
            p.removedBy = rs.wasNull() ? null : removedBy;
            p.removedReason = rs.getString("removed_reason");
            p.authorHandle = rs.getString("author_handle");
            p.authorDisplayName = rs.getString("author_display_name");
            p.authorFirstName = rs.getString("author_first_name");
            p.authorLastName = rs.getString("author_last_name");
            p.authorProfileImageUrl = rs.getString("author_profile_image_url");
            long displayCommunityId = rs.getLong("author_display_community_id");
            p.authorDisplayCommunityId = rs.wasNull() ? null : displayCommunityId;
            p.authorDisplayCommunityName = rs.getString("author_display_community_name");
            p.authorDisplayCommunityKind = rs.getString("author_display_community_kind");
            p.authorDisplayCommunitySpecializationType = rs.getString("author_display_community_specialization_type");
            long displaySpecializationId = rs.getLong("author_display_specialization_id");
            p.authorDisplaySpecializationId = rs.wasNull() ? null : displaySpecializationId;
            p.authorDisplaySpecializationName = rs.getString("author_display_specialization_name");
            p.authorDisplaySpecializationKind = rs.getString("author_display_specialization_kind");
            p.authorDisplaySpecializationType = rs.getString("author_display_specialization_type");
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

    public java.util.List<PostRow> findByIds(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) return java.util.List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query(
                BASE_SELECT + "WHERE p.id IN (" + placeholders + ") AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL)",
                MAPPER,
                ids.toArray()
        );
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

    public java.util.List<PostRow> findNew(Long communityId, java.time.OffsetDateTime cursorTs, Long cursorId, int limit,
                                           long viewerUserId, long viewerPrincipalId, boolean hideAnonymousPosts) {
        String base = BASE_SELECT + "WHERE p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) ";
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (communityId != null) {
            base += "AND p.community_id = ? ";
            args.add(communityId);
        }
        if (hideAnonymousPosts) {
            base += hideAnonymousFilter(true);
            args.add(viewerUserId);
        }
        base += blocksFilter();
        args.add(viewerPrincipalId);
        args.add(viewerPrincipalId);
        if (cursorTs == null || cursorId == null) {
            if (communityId != null) {
                args.add(limit);
                return jdbc.query(base + "ORDER BY p.created_at DESC, p.id DESC LIMIT ?", MAPPER, args.toArray());
            }
            args.add(limit);
            return jdbc.query(base + "ORDER BY p.created_at DESC, p.id DESC LIMIT ?", MAPPER, args.toArray());
        }
        String paging = "AND (p.created_at < ? OR (p.created_at = ? AND p.id < ?)) " +
                "ORDER BY p.created_at DESC, p.id DESC LIMIT ?";
        args.add(cursorTs);
        args.add(cursorTs);
        args.add(cursorId);
        args.add(limit);
        return jdbc.query(base + paging, MAPPER, args.toArray());
    }

    public java.util.List<PostRow> findPopular(java.time.OffsetDateTime asOf, java.time.OffsetDateTime since, Long cursorScore,
                                               java.time.OffsetDateTime cursorTs, Long cursorId, int limit,
                                               long viewerUserId, long viewerPrincipalId, boolean hideAnonymousPosts) {
        String scoreExpr = "((p.likes_count * 2 + p.comments_count + p.share_count) * 1000 - " +
                "FLOOR(EXTRACT(EPOCH FROM (?::timestamptz - p.created_at)) / 3600))";
        String base = "SELECT p.id, p.author_id, p.author_principal_id, p.is_anon, p.anon_profile_id, p.anon_company_id, " +
                "p.company_id, p.community_id, c.name AS community_name, c.kind AS community_kind, " +
                "p.content, p.media_asset_id, p.likes_count, p.comments_count, p.share_count, p.repost_count, p.created_at, " +
                "p.removed_at, p.removed_by, p.removed_reason, " +
                "COALESCE(u.handle, ap.handle) AS author_handle, u.display_name AS author_display_name, " +
                "u.first_name AS author_first_name, u.last_name AS author_last_name, " +
                "u.profile_image_url AS author_profile_image_url, " +
                "dc.id AS author_display_community_id, dc.name AS author_display_community_name, " +
                "dc.kind AS author_display_community_kind, dc.specialization_type AS author_display_community_specialization_type, " +
                "ds.id AS author_display_specialization_id, ds.name AS author_display_specialization_name, " +
                "ds.kind AS author_display_specialization_kind, ds.specialization_type AS author_display_specialization_type, " +
                "CASE WHEN p.is_anon THEN true ELSE COALESCE(u.is_anonymous, false) END AS author_is_anonymous, " +
                scoreExpr + " AS score FROM posts p " +
                "LEFT JOIN communities c ON c.id = p.community_id " +
                "LEFT JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL " +
                "LEFT JOIN community_verifications cv ON cv.user_id = u.id AND cv.community_id = u.display_community_id " +
                "AND cv.verified = true AND (cv.expires_at IS NULL OR cv.expires_at > now()) " +
                "LEFT JOIN communities dc ON dc.id = cv.community_id " +
                "LEFT JOIN communities ds ON ds.id = u.display_specialization_id " +
                "AND ds.kind = 'specialization' AND ds.specialization_type IN ('major','department') " +
                "LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id " +
                "WHERE p.created_at >= ? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL)";
        if (hideAnonymousPosts) {
            base += " " + hideAnonymousFilter(true);
        }
        base += " " + blocksFilter();
        if (cursorScore == null || cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT id, author_id, author_principal_id, is_anon, anon_profile_id, anon_company_id, company_id, community_id, " +
                            "community_name, community_kind, content, media_asset_id, likes_count, comments_count, share_count, repost_count, created_at, " +
                            "removed_at, removed_by, removed_reason, " +
                            "author_handle, author_display_name, author_first_name, author_last_name, author_profile_image_url, " +
                            "author_display_community_id, author_display_community_name, author_display_community_kind, " +
                            "author_display_community_specialization_type, " +
                            "author_display_specialization_id, author_display_specialization_name, author_display_specialization_kind, " +
                            "author_display_specialization_type, author_is_anonymous " +
                            "FROM (" + base + ") s ORDER BY score DESC, created_at DESC, id DESC LIMIT ?",
                    MAPPER,
                    hideAnonymousPosts
                            ? new Object[]{asOf, since, viewerUserId, viewerPrincipalId, viewerPrincipalId, limit}
                            : new Object[]{asOf, since, viewerPrincipalId, viewerPrincipalId, limit}
            );
        }
        return jdbc.query(
                "SELECT id, author_id, author_principal_id, is_anon, anon_profile_id, anon_company_id, company_id, community_id, " +
                        "community_name, community_kind, content, media_asset_id, likes_count, comments_count, share_count, repost_count, created_at, " +
                        "removed_at, removed_by, removed_reason, " +
                        "author_handle, author_display_name, author_first_name, author_last_name, author_profile_image_url, " +
                        "author_display_community_id, author_display_community_name, author_display_community_kind, " +
                        "author_display_community_specialization_type, " +
                        "author_display_specialization_id, author_display_specialization_name, author_display_specialization_kind, " +
                        "author_display_specialization_type, author_is_anonymous " +
                        "FROM (" + base + ") s WHERE (score < ? OR (score = ? AND (created_at < ? OR (created_at = ? AND id < ?)))) " +
                        "ORDER BY score DESC, created_at DESC, id DESC LIMIT ?",
                MAPPER,
                hideAnonymousPosts
                        ? new Object[]{asOf, since, viewerUserId, viewerPrincipalId, viewerPrincipalId, cursorScore, cursorScore, cursorTs, cursorTs, cursorId, limit}
                        : new Object[]{asOf, since, viewerPrincipalId, viewerPrincipalId, cursorScore, cursorScore, cursorTs, cursorTs, cursorId, limit}
        );
    }

    public java.util.List<PostRow> findPopularByCommunity(long communityId, java.time.OffsetDateTime asOf, java.time.OffsetDateTime since,
                                                          Long cursorScore, java.time.OffsetDateTime cursorTs, Long cursorId, int limit,
                                                          long viewerUserId, long viewerPrincipalId, boolean hideAnonymousPosts) {
        String scoreExpr = "((p.likes_count * 2 + p.comments_count + p.share_count) * 1000 - " +
                "FLOOR(EXTRACT(EPOCH FROM (?::timestamptz - p.created_at)) / 3600))";
        String base = "SELECT p.id, p.author_id, p.author_principal_id, p.is_anon, p.anon_profile_id, p.anon_company_id, " +
                "p.company_id, p.community_id, c.name AS community_name, c.kind AS community_kind, " +
                "p.content, p.media_asset_id, p.likes_count, p.comments_count, p.share_count, p.repost_count, p.created_at, " +
                "p.removed_at, p.removed_by, p.removed_reason, " +
                "COALESCE(u.handle, ap.handle) AS author_handle, u.display_name AS author_display_name, " +
                "u.first_name AS author_first_name, u.last_name AS author_last_name, " +
                "u.profile_image_url AS author_profile_image_url, " +
                "dc.id AS author_display_community_id, dc.name AS author_display_community_name, " +
                "dc.kind AS author_display_community_kind, dc.specialization_type AS author_display_community_specialization_type, " +
                "ds.id AS author_display_specialization_id, ds.name AS author_display_specialization_name, " +
                "ds.kind AS author_display_specialization_kind, ds.specialization_type AS author_display_specialization_type, " +
                "CASE WHEN p.is_anon THEN true ELSE COALESCE(u.is_anonymous, false) END AS author_is_anonymous, " +
                scoreExpr + " AS score FROM posts p " +
                "LEFT JOIN communities c ON c.id = p.community_id " +
                "LEFT JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL " +
                "LEFT JOIN community_verifications cv ON cv.user_id = u.id AND cv.community_id = u.display_community_id " +
                "AND cv.verified = true AND (cv.expires_at IS NULL OR cv.expires_at > now()) " +
                "LEFT JOIN communities dc ON dc.id = cv.community_id " +
                "LEFT JOIN communities ds ON ds.id = u.display_specialization_id " +
                "AND ds.kind = 'specialization' AND ds.specialization_type IN ('major','department') " +
                "LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id " +
                "WHERE p.created_at >= ? AND p.removed_at IS NULL AND p.community_id = ? " +
                "AND (p.author_id IS NULL OR u.id IS NOT NULL)";
        if (hideAnonymousPosts) {
            base += " " + hideAnonymousFilter(true);
        }
        base += " " + blocksFilter();
        if (cursorScore == null || cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT id, author_id, author_principal_id, is_anon, anon_profile_id, anon_company_id, company_id, community_id, " +
                            "community_name, community_kind, content, media_asset_id, likes_count, comments_count, share_count, repost_count, created_at, " +
                            "removed_at, removed_by, removed_reason, " +
                            "author_handle, author_display_name, author_first_name, author_last_name, author_profile_image_url, " +
                            "author_display_community_id, author_display_community_name, author_display_community_kind, " +
                            "author_display_community_specialization_type, " +
                            "author_display_specialization_id, author_display_specialization_name, author_display_specialization_kind, " +
                            "author_display_specialization_type, author_is_anonymous " +
                            "FROM (" + base + ") s ORDER BY score DESC, created_at DESC, id DESC LIMIT ?",
                    MAPPER,
                    hideAnonymousPosts
                            ? new Object[]{asOf, since, communityId, viewerUserId, viewerPrincipalId, viewerPrincipalId, limit}
                            : new Object[]{asOf, since, communityId, viewerPrincipalId, viewerPrincipalId, limit}
            );
        }
        return jdbc.query(
                "SELECT id, author_id, author_principal_id, is_anon, anon_profile_id, anon_company_id, company_id, community_id, " +
                        "community_name, community_kind, content, media_asset_id, likes_count, comments_count, share_count, repost_count, created_at, " +
                        "removed_at, removed_by, removed_reason, " +
                        "author_handle, author_display_name, author_first_name, author_last_name, author_profile_image_url, " +
                        "author_display_community_id, author_display_community_name, author_display_community_kind, " +
                        "author_display_community_specialization_type, " +
                        "author_display_specialization_id, author_display_specialization_name, author_display_specialization_kind, " +
                        "author_display_specialization_type, author_is_anonymous " +
                        "FROM (" + base + ") s WHERE (score < ? OR (score = ? AND (created_at < ? OR (created_at = ? AND id < ?)))) " +
                        "ORDER BY score DESC, created_at DESC, id DESC LIMIT ?",
                MAPPER,
                hideAnonymousPosts
                        ? new Object[]{asOf, since, communityId, viewerUserId, viewerPrincipalId, viewerPrincipalId, cursorScore, cursorScore, cursorTs, cursorTs, cursorId, limit}
                        : new Object[]{asOf, since, communityId, viewerPrincipalId, viewerPrincipalId, cursorScore, cursorScore, cursorTs, cursorTs, cursorId, limit}
        );
    }

    public java.util.List<PostRow> findByAuthor(long authorId, java.time.OffsetDateTime cursorTs, Long cursorId, int limit,
                                               long viewerUserId, boolean hideAnonymousPosts) {
        String filter = hideAnonymousPosts ? hideAnonymousFilter(true) : "";
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    BASE_SELECT + "WHERE p.author_id=? AND p.removed_at IS NULL AND u.id IS NOT NULL " + filter +
                            "ORDER BY p.created_at DESC, p.id DESC LIMIT ?",
                    MAPPER,
                    hideAnonymousPosts ? new Object[]{authorId, viewerUserId, limit} : new Object[]{authorId, limit}
            );
        } else {
            return jdbc.query(
                    BASE_SELECT + "WHERE p.author_id=? AND p.removed_at IS NULL AND u.id IS NOT NULL " + filter +
                            "AND (p.created_at < ? OR (p.created_at = ? AND p.id < ?)) " +
                            "ORDER BY p.created_at DESC, p.id DESC LIMIT ?",
                    MAPPER,
                    hideAnonymousPosts
                            ? new Object[]{authorId, viewerUserId, cursorTs, cursorTs, cursorId, limit}
                            : new Object[]{authorId, cursorTs, cursorTs, cursorId, limit}
            );
        }
    }

    public java.util.List<PostRow> findByAuthorPrincipal(long authorPrincipalId, java.time.OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    BASE_SELECT + "WHERE p.author_principal_id=? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) " +
                            "ORDER BY p.created_at DESC, p.id DESC LIMIT ?",
                    MAPPER, authorPrincipalId, limit
            );
        } else {
            return jdbc.query(
                    BASE_SELECT + "WHERE p.author_principal_id=? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) " +
                            "AND (p.created_at < ? OR (p.created_at = ? AND p.id < ?)) " +
                        "ORDER BY p.created_at DESC, p.id DESC LIMIT ?",
                MAPPER, authorPrincipalId, cursorTs, cursorTs, cursorId, limit
            );
        }
    }

    public java.util.List<PostRow> findByHashtag(long companyId, String name, java.time.OffsetDateTime cursorTs, Long cursorId, int limit,
                                                 long viewerUserId, boolean hideAnonymousPosts) {
        String base = BASE_SELECT +
                "JOIN hashtag_posts hp ON hp.post_id = p.id " +
                "JOIN hashtags h ON h.id = hp.hashtag_id " +
                "WHERE h.company_id = ? AND h.name = ? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) ";
        if (hideAnonymousPosts) {
            base += hideAnonymousFilter(true);
        }
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    base + "ORDER BY p.created_at ASC, p.id ASC LIMIT ?",
                    MAPPER,
                    hideAnonymousPosts ? new Object[]{companyId, name, viewerUserId, limit} : new Object[]{companyId, name, limit}
            );
        }
        return jdbc.query(
                base + "AND (p.created_at > ? OR (p.created_at = ? AND p.id > ?)) " +
                        "ORDER BY p.created_at ASC, p.id ASC LIMIT ?",
                MAPPER,
                hideAnonymousPosts
                        ? new Object[]{companyId, name, viewerUserId, cursorTs, cursorTs, cursorId, limit}
                        : new Object[]{companyId, name, cursorTs, cursorTs, cursorId, limit}
        );
    }

    public java.util.List<TrendingRow> findTrendingWithMedia(java.time.OffsetDateTime asOf, java.time.OffsetDateTime since, Long communityId, int limit,
                                                             long viewerUserId, long viewerPrincipalId, boolean hideAnonymousPosts) {
        String scoreExpr = "((p.likes_count * 2 + p.comments_count + p.share_count) * 1000 - " +
                "FLOOR(EXTRACT(EPOCH FROM (?::timestamptz - p.created_at)) / 3600))";
        String base = "SELECT p.id, p.author_id, p.author_principal_id, p.is_anon, p.anon_profile_id, p.anon_company_id, " +
                "p.company_id, p.community_id, p.content, p.media_asset_id, p.likes_count, p.comments_count, p.share_count, p.repost_count, p.created_at, " +
                "COALESCE(u.handle, ap.handle) AS author_handle, u.display_name AS author_display_name, " +
                "u.first_name AS author_first_name, u.last_name AS author_last_name, " +
                "u.profile_image_url AS author_profile_image_url, " +
                "dc.id AS author_display_community_id, dc.name AS author_display_community_name, " +
                "dc.kind AS author_display_community_kind, dc.specialization_type AS author_display_community_specialization_type, " +
                "ds.id AS author_display_specialization_id, ds.name AS author_display_specialization_name, " +
                "ds.kind AS author_display_specialization_kind, ds.specialization_type AS author_display_specialization_type, " +
                "CASE WHEN p.is_anon THEN true ELSE COALESCE(u.is_anonymous, false) END AS author_is_anonymous, " +
                "c.name AS community_name, c.kind AS community_kind, " +
                scoreExpr + " AS score FROM posts p " +
                "JOIN communities c ON c.id = p.community_id " +
                "LEFT JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL " +
                "LEFT JOIN community_verifications cv ON cv.user_id = u.id AND cv.community_id = u.display_community_id " +
                "AND cv.verified = true AND (cv.expires_at IS NULL OR cv.expires_at > now()) " +
                "LEFT JOIN communities dc ON dc.id = cv.community_id " +
                "LEFT JOIN communities ds ON ds.id = u.display_specialization_id " +
                "AND ds.kind = 'specialization' AND ds.specialization_type IN ('major','department') " +
                "LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id " +
                "WHERE p.media_asset_id IS NOT NULL AND p.created_at >= ? AND p.removed_at IS NULL AND (p.author_id IS NULL OR u.id IS NOT NULL) ";
        if (communityId != null) {
            base += "AND p.community_id = ? ";
        }
        if (hideAnonymousPosts) {
            base += hideAnonymousFilter(true);
        }
        base += blocksFilter();
        base += "ORDER BY score DESC, p.created_at DESC, p.id DESC LIMIT ?";

        Object[] params;
        if (communityId != null) {
            params = hideAnonymousPosts
                    ? new Object[]{asOf, since, communityId, viewerUserId, viewerPrincipalId, viewerPrincipalId, limit}
                    : new Object[]{asOf, since, communityId, viewerPrincipalId, viewerPrincipalId, limit};
        } else {
            params = hideAnonymousPosts
                    ? new Object[]{asOf, since, viewerUserId, viewerPrincipalId, viewerPrincipalId, limit}
                    : new Object[]{asOf, since, viewerPrincipalId, viewerPrincipalId, limit};
        }
        return jdbc.query(base, TRENDING_MAPPER, params);
    }

    public java.util.List<ScoredPostRow> searchCompanyPosts(long companyId, String query, String prefixQuery, java.time.OffsetDateTime asOf,
                                                           Long cursorScore, java.time.OffsetDateTime cursorTs, Long cursorId, int limit,
                                                           long viewerUserId, long viewerPrincipalId, boolean hideAnonymousPosts) {
        String vectorEn = "to_tsvector('english', COALESCE(p.content, ''))";
        String vectorSimple = "to_tsvector('simple', COALESCE(p.content, ''))";
        String match = "(" + vectorEn + " @@ q.q_web OR (q.q_prefix IS NOT NULL AND " + vectorSimple + " @@ q.q_prefix))";
        String rank = "GREATEST(" +
                "ts_rank_cd(" + vectorEn + ", q.q_web), " +
                "COALESCE(ts_rank_cd(" + vectorSimple + ", q.q_prefix), 0)" +
                ")";
        String engagement = "LEAST(200000, LN(1 + (p.likes_count * 2 + p.comments_count + p.share_count)) * 50000)";
        String recency = "LEAST(150000, (1.0 / (1.0 + EXTRACT(EPOCH FROM (?::timestamptz - p.created_at)) / 86400.0)) * 150000)";
        String scoreExpr = "CAST((" + rank + " * 1000000 + " + engagement + " + " + recency + ") AS BIGINT)";

        String base =
                "WITH q AS (" +
                        "SELECT websearch_to_tsquery('english', ?) AS q_web, " +
                        "to_tsquery('simple', NULLIF(?, '')) AS q_prefix" +
                        ") " +
                        "SELECT p.id, p.author_id, p.author_principal_id, p.is_anon, p.anon_profile_id, p.anon_company_id, " +
                        "p.company_id, p.community_id, c.name AS community_name, c.kind AS community_kind, " +
                        "p.content, p.media_asset_id, p.likes_count, p.comments_count, p.share_count, p.repost_count, p.created_at, " +
                        "p.removed_at, p.removed_by, p.removed_reason, " +
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
                        scoreExpr + " AS score " +
                        "FROM posts p " +
                        "LEFT JOIN communities c ON c.id = p.community_id " +
                        "LEFT JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL " +
                        "LEFT JOIN community_verifications cv ON cv.user_id = u.id AND cv.community_id = u.display_community_id " +
                        "AND cv.verified = true AND (cv.expires_at IS NULL OR cv.expires_at > now()) " +
                        "LEFT JOIN communities dc ON dc.id = cv.community_id " +
                        "LEFT JOIN communities ds ON ds.id = u.display_specialization_id " +
                        "AND ds.kind = 'specialization' AND ds.specialization_type IN ('major','department') " +
                        "LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id " +
                        "CROSS JOIN q " +
                        "WHERE p.company_id = ? " +
                        "AND p.removed_at IS NULL " +
                        "AND (p.author_id IS NULL OR u.id IS NOT NULL) " +
                        (hideAnonymousPosts ? hideAnonymousFilter(true) : "") +
                        blocksFilter() +
                        "AND " + match;

        if (cursorScore == null || cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT * FROM (" + base + ") s ORDER BY score DESC, created_at DESC, id DESC LIMIT ?",
                    SCORED_MAPPER,
                    hideAnonymousPosts
                            ? new Object[]{query, prefixQuery, asOf, companyId, viewerUserId, viewerPrincipalId, viewerPrincipalId, limit}
                            : new Object[]{query, prefixQuery, asOf, companyId, viewerPrincipalId, viewerPrincipalId, limit}
            );
        }
        return jdbc.query(
                "SELECT * FROM (" + base + ") s " +
                        "WHERE (score < ? OR (score = ? AND (created_at < ? OR (created_at = ? AND id < ?)))) " +
                        "ORDER BY score DESC, created_at DESC, id DESC LIMIT ?",
                SCORED_MAPPER,
                hideAnonymousPosts
                        ? new Object[]{query, prefixQuery, asOf, companyId, viewerUserId, viewerPrincipalId, viewerPrincipalId, cursorScore, cursorScore, cursorTs, cursorTs, cursorId, limit}
                        : new Object[]{query, prefixQuery, asOf, companyId, viewerPrincipalId, viewerPrincipalId, cursorScore, cursorScore, cursorTs, cursorTs, cursorId, limit}
        );
    }

    public void incrementCommentsCount(long postId) {
        jdbc.update("UPDATE posts SET comments_count = comments_count + 1 WHERE id = ?", postId);
    }

    public void decrementCommentsCount(long postId) {
        jdbc.update("UPDATE posts SET comments_count = GREATEST(comments_count - 1, 0) WHERE id = ?", postId);
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

    public boolean updateContent(long postId, String content) {
        return jdbc.update(
                "UPDATE posts SET content = ? WHERE id = ? AND removed_at IS NULL",
                content, postId
        ) > 0;
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
        public String communityName;
        public String communityKind;
        public String content;
        public Long mediaAssetId;
        public int likesCount;
        public int commentsCount;
        public int shareCount;
        public int repostCount;
        public OffsetDateTime createdAt;
        public OffsetDateTime removedAt;
        public Long removedBy;
        public String removedReason;
        public String authorHandle;
        public String authorDisplayName;
        public String authorFirstName;
        public String authorLastName;
        public String authorProfileImageUrl;
        public Long authorDisplayCommunityId;
        public String authorDisplayCommunityName;
        public String authorDisplayCommunityKind;
        public String authorDisplayCommunitySpecializationType;
        public Long authorDisplaySpecializationId;
        public String authorDisplaySpecializationName;
        public String authorDisplaySpecializationKind;
        public String authorDisplaySpecializationType;
        public boolean authorIsAnonymous;
        public boolean userLiked;
        public boolean isSaved;
        public boolean viewerHasReposted;
        public java.util.List<RepostBannerUser> repostedByFollowedUsers;
        public Integer repostedByFollowedUsersCount;
    }

    public record RepostBannerUser(long userId, String username) {}

    public static class ScoredPostRow extends PostRow {
        public long score;
    }

    public static class TrendingRow extends PostRow {
        public String communityName;
        public String communityKind;
    }

    private static final RowMapper<ScoredPostRow> SCORED_MAPPER = new RowMapper<>() {
        @Override
        public ScoredPostRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ScoredPostRow p = new ScoredPostRow();
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
            p.communityName = rs.getString("community_name");
            p.communityKind = rs.getString("community_kind");
            p.content = rs.getString("content");
            long media = rs.getLong("media_asset_id");
            p.mediaAssetId = rs.wasNull() ? null : media;
            p.likesCount = rs.getInt("likes_count");
            p.commentsCount = rs.getInt("comments_count");
            p.shareCount = rs.getInt("share_count");
            p.repostCount = rs.getInt("repost_count");
            p.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            p.removedAt = rs.getObject("removed_at", OffsetDateTime.class);
            long removedBy = rs.getLong("removed_by");
            p.removedBy = rs.wasNull() ? null : removedBy;
            p.removedReason = rs.getString("removed_reason");
            p.authorHandle = rs.getString("author_handle");
            p.authorDisplayName = rs.getString("author_display_name");
            p.authorFirstName = rs.getString("author_first_name");
            p.authorLastName = rs.getString("author_last_name");
            p.authorProfileImageUrl = rs.getString("author_profile_image_url");
            long displayCommunityId = rs.getLong("author_display_community_id");
            p.authorDisplayCommunityId = rs.wasNull() ? null : displayCommunityId;
            p.authorDisplayCommunityName = rs.getString("author_display_community_name");
            p.authorDisplayCommunityKind = rs.getString("author_display_community_kind");
            p.authorDisplayCommunitySpecializationType = rs.getString("author_display_community_specialization_type");
            long displaySpecializationId = rs.getLong("author_display_specialization_id");
            p.authorDisplaySpecializationId = rs.wasNull() ? null : displaySpecializationId;
            p.authorDisplaySpecializationName = rs.getString("author_display_specialization_name");
            p.authorDisplaySpecializationKind = rs.getString("author_display_specialization_kind");
            p.authorDisplaySpecializationType = rs.getString("author_display_specialization_type");
            p.authorIsAnonymous = rs.getBoolean("author_is_anonymous");
            p.score = rs.getLong("score");
            return p;
        }
    };

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
            p.repostCount = rs.getInt("repost_count");
            p.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            p.authorHandle = rs.getString("author_handle");
            p.authorDisplayName = rs.getString("author_display_name");
            p.authorFirstName = rs.getString("author_first_name");
            p.authorLastName = rs.getString("author_last_name");
            p.authorProfileImageUrl = rs.getString("author_profile_image_url");
            long displayCommunityId = rs.getLong("author_display_community_id");
            p.authorDisplayCommunityId = rs.wasNull() ? null : displayCommunityId;
            p.authorDisplayCommunityName = rs.getString("author_display_community_name");
            p.authorDisplayCommunityKind = rs.getString("author_display_community_kind");
            p.authorDisplayCommunitySpecializationType = rs.getString("author_display_community_specialization_type");
            long displaySpecializationId = rs.getLong("author_display_specialization_id");
            p.authorDisplaySpecializationId = rs.wasNull() ? null : displaySpecializationId;
            p.authorDisplaySpecializationName = rs.getString("author_display_specialization_name");
            p.authorDisplaySpecializationKind = rs.getString("author_display_specialization_kind");
            p.authorDisplaySpecializationType = rs.getString("author_display_specialization_type");
            p.authorIsAnonymous = rs.getBoolean("author_is_anonymous");
            p.communityName = rs.getString("community_name");
            p.communityKind = rs.getString("community_kind");
            return p;
        }
    };
}
