package com.looped.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class AdminCommentsRepository {
    private final JdbcTemplate jdbc;

    public AdminCommentsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> findById(long id) {
        var list = jdbc.query(
                """
                SELECT c.id, c.post_id, c.user_id, c.author_principal_id, c.company_id,
                       c.content, c.media_asset_id, c.parent_id,
                       c.likes_count, c.reply_count, c.created_at, c.deleted_at,
                       c.visibility, c.quarantined_at, c.quarantine_reason,
                       c.removed_at, c.removed_by, c.removed_reason,
                       p.kind AS author_kind, p.user_id AS author_user_id, p.anon_profile_id AS author_anon_profile_id,
                       COALESCE(u.handle, ap.handle) AS author_handle,
                       u.display_name AS author_display_name,
                       u.profile_image_url AS author_profile_image_url,
                       COALESCE(u.company_id, ap.company_id) AS author_company_id,
                       CASE WHEN p.kind = 'anon' THEN true ELSE COALESCE(u.is_anonymous, false) END AS author_is_anonymous
                FROM comments c
                JOIN principals p ON p.id = c.author_principal_id
                LEFT JOIN users u ON u.id = p.user_id
                LEFT JOIN anonymous_profiles ap ON ap.id = p.anon_profile_id
                WHERE c.id = ?
                LIMIT 1
                """,
                (rs, rowNum) -> {
                    Row r = new Row();
                    r.id = rs.getLong("id");
                    r.postId = rs.getLong("post_id");
                    Long userId = rs.getObject("user_id", Long.class);
                    r.userId = userId;
                    r.authorPrincipalId = rs.getLong("author_principal_id");
                    r.companyId = rs.getLong("company_id");
                    r.content = rs.getString("content");
                    r.mediaAssetId = rs.getObject("media_asset_id", Long.class);
                    r.parentId = rs.getObject("parent_id", Long.class);
                    r.likesCount = rs.getInt("likes_count");
                    r.replyCount = rs.getInt("reply_count");
                    r.createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    r.deletedAt = rs.getObject("deleted_at", OffsetDateTime.class);
                    r.visibility = rs.getString("visibility");
                    r.quarantinedAt = rs.getObject("quarantined_at", OffsetDateTime.class);
                    r.quarantineReason = rs.getString("quarantine_reason");
                    r.removedAt = rs.getObject("removed_at", OffsetDateTime.class);
                    r.removedBy = rs.getObject("removed_by", Long.class);
                    r.removedReason = rs.getString("removed_reason");
                    r.authorKind = rs.getString("author_kind");
                    r.authorUserId = rs.getObject("author_user_id", Long.class);
                    r.authorAnonProfileId = rs.getObject("author_anon_profile_id", Long.class);
                    r.authorHandle = rs.getString("author_handle");
                    r.authorDisplayName = rs.getString("author_display_name");
                    r.authorProfileImageUrl = rs.getString("author_profile_image_url");
                    r.authorCompanyId = rs.getObject("author_company_id", Long.class);
                    r.authorIsAnonymous = rs.getBoolean("author_is_anonymous");
                    return r;
                },
                id
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public static class Row {
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
        public String authorKind;
        public Long authorUserId;
        public Long authorAnonProfileId;
        public String authorHandle;
        public String authorDisplayName;
        public String authorProfileImageUrl;
        public Long authorCompanyId;
        public boolean authorIsAnonymous;
    }
}

