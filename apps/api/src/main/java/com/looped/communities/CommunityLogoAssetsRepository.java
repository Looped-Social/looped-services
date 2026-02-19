package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class CommunityLogoAssetsRepository {
    private final JdbcTemplate jdbc;

    public CommunityLogoAssetsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(long communityId, long mediaAssetId) {
        int rows = jdbc.update(
                "INSERT INTO community_logo_assets(community_id, media_asset_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                communityId, mediaAssetId
        );
        return rows > 0;
    }

    public List<LogoAssetRow> listByCommunity(long communityId) {
        return jdbc.query(
                "SELECT cla.id, cla.community_id, cla.media_asset_id, cla.created_at, " +
                        "ma.s3_key, ma.mime_type, ma.created_at AS media_created_at " +
                        "FROM community_logo_assets cla " +
                        "JOIN media_assets ma ON ma.id = cla.media_asset_id " +
                        "WHERE cla.community_id = ? " +
                        "ORDER BY cla.created_at DESC, cla.id DESC",
                (rs, rowNum) -> {
                    LogoAssetRow row = new LogoAssetRow();
                    row.id = rs.getLong("id");
                    row.communityId = rs.getLong("community_id");
                    row.mediaAssetId = rs.getLong("media_asset_id");
                    row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    row.s3Key = rs.getString("s3_key");
                    row.mimeType = rs.getString("mime_type");
                    row.mediaCreatedAt = rs.getObject("media_created_at", OffsetDateTime.class);
                    return row;
                },
                communityId
        );
    }

    public Optional<LogoAssetRow> findByCommunityAndKey(long communityId, String s3Key) {
        var rows = jdbc.query(
                "SELECT cla.id, cla.community_id, cla.media_asset_id, cla.created_at, " +
                        "ma.s3_key, ma.mime_type, ma.created_at AS media_created_at " +
                        "FROM community_logo_assets cla " +
                        "JOIN media_assets ma ON ma.id = cla.media_asset_id " +
                        "WHERE cla.community_id = ? AND ma.s3_key = ? " +
                        "LIMIT 1",
                (rs, rowNum) -> {
                    LogoAssetRow row = new LogoAssetRow();
                    row.id = rs.getLong("id");
                    row.communityId = rs.getLong("community_id");
                    row.mediaAssetId = rs.getLong("media_asset_id");
                    row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    row.s3Key = rs.getString("s3_key");
                    row.mimeType = rs.getString("mime_type");
                    row.mediaCreatedAt = rs.getObject("media_created_at", OffsetDateTime.class);
                    return row;
                },
                communityId, s3Key
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<LogoAssetRow> findByIdAndCommunity(long id, long communityId) {
        var rows = jdbc.query(
                "SELECT cla.id, cla.community_id, cla.media_asset_id, cla.created_at, " +
                        "ma.s3_key, ma.mime_type, ma.created_at AS media_created_at " +
                        "FROM community_logo_assets cla " +
                        "JOIN media_assets ma ON ma.id = cla.media_asset_id " +
                        "WHERE cla.id = ? AND cla.community_id = ? " +
                        "LIMIT 1",
                (rs, rowNum) -> {
                    LogoAssetRow row = new LogoAssetRow();
                    row.id = rs.getLong("id");
                    row.communityId = rs.getLong("community_id");
                    row.mediaAssetId = rs.getLong("media_asset_id");
                    row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    row.s3Key = rs.getString("s3_key");
                    row.mimeType = rs.getString("mime_type");
                    row.mediaCreatedAt = rs.getObject("media_created_at", OffsetDateTime.class);
                    return row;
                },
                id, communityId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean deleteByIdAndCommunity(long id, long communityId) {
        int rows = jdbc.update(
                "DELETE FROM community_logo_assets WHERE id = ? AND community_id = ?",
                id, communityId
        );
        return rows > 0;
    }

    public static class LogoAssetRow {
        public long id;
        public long communityId;
        public long mediaAssetId;
        public String s3Key;
        public String mimeType;
        public OffsetDateTime createdAt;
        public OffsetDateTime mediaCreatedAt;
    }
}
