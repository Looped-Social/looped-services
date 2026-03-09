package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class SpecializationBrandingAssetsRepository {
    private final JdbcTemplate jdbc;

    public SpecializationBrandingAssetsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(long communityId, long mediaAssetId, String slot) {
        int rows = jdbc.update(
                "INSERT INTO specialization_branding_assets(community_id, media_asset_id, slot) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                communityId, mediaAssetId, slot
        );
        return rows > 0;
    }

    public List<BrandingAssetRow> listByCommunity(long communityId) {
        return jdbc.query(
                "SELECT sba.id, sba.community_id, sba.media_asset_id, sba.slot, sba.created_at, " +
                        "ma.s3_key, ma.mime_type, ma.width, ma.height, ma.created_at AS media_created_at " +
                        "FROM specialization_branding_assets sba " +
                        "JOIN media_assets ma ON ma.id = sba.media_asset_id " +
                        "WHERE sba.community_id = ? " +
                        "ORDER BY sba.created_at DESC, sba.id DESC",
                (rs, rowNum) -> {
                    BrandingAssetRow row = new BrandingAssetRow();
                    row.id = rs.getLong("id");
                    row.communityId = rs.getLong("community_id");
                    row.mediaAssetId = rs.getLong("media_asset_id");
                    row.slot = rs.getString("slot");
                    row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    row.s3Key = rs.getString("s3_key");
                    row.mimeType = rs.getString("mime_type");
                    row.width = rs.getObject("width", Integer.class);
                    row.height = rs.getObject("height", Integer.class);
                    row.mediaCreatedAt = rs.getObject("media_created_at", OffsetDateTime.class);
                    return row;
                },
                communityId
        );
    }

    public Optional<BrandingAssetRow> findByIdAndCommunity(long id, long communityId) {
        var rows = jdbc.query(
                "SELECT sba.id, sba.community_id, sba.media_asset_id, sba.slot, sba.created_at, " +
                        "ma.s3_key, ma.mime_type, ma.width, ma.height, ma.created_at AS media_created_at " +
                        "FROM specialization_branding_assets sba " +
                        "JOIN media_assets ma ON ma.id = sba.media_asset_id " +
                        "WHERE sba.id = ? AND sba.community_id = ? " +
                        "LIMIT 1",
                (rs, rowNum) -> {
                    BrandingAssetRow row = new BrandingAssetRow();
                    row.id = rs.getLong("id");
                    row.communityId = rs.getLong("community_id");
                    row.mediaAssetId = rs.getLong("media_asset_id");
                    row.slot = rs.getString("slot");
                    row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    row.s3Key = rs.getString("s3_key");
                    row.mimeType = rs.getString("mime_type");
                    row.width = rs.getObject("width", Integer.class);
                    row.height = rs.getObject("height", Integer.class);
                    row.mediaCreatedAt = rs.getObject("media_created_at", OffsetDateTime.class);
                    return row;
                },
                id, communityId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<BrandingAssetRow> findByCommunitySlotAndMediaAssetId(long communityId, String slot, long mediaAssetId) {
        var rows = jdbc.query(
                "SELECT sba.id, sba.community_id, sba.media_asset_id, sba.slot, sba.created_at, " +
                        "ma.s3_key, ma.mime_type, ma.width, ma.height, ma.created_at AS media_created_at " +
                        "FROM specialization_branding_assets sba " +
                        "JOIN media_assets ma ON ma.id = sba.media_asset_id " +
                        "WHERE sba.community_id = ? AND sba.slot = ? AND sba.media_asset_id = ? " +
                        "LIMIT 1",
                (rs, rowNum) -> {
                    BrandingAssetRow row = new BrandingAssetRow();
                    row.id = rs.getLong("id");
                    row.communityId = rs.getLong("community_id");
                    row.mediaAssetId = rs.getLong("media_asset_id");
                    row.slot = rs.getString("slot");
                    row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    row.s3Key = rs.getString("s3_key");
                    row.mimeType = rs.getString("mime_type");
                    row.width = rs.getObject("width", Integer.class);
                    row.height = rs.getObject("height", Integer.class);
                    row.mediaCreatedAt = rs.getObject("media_created_at", OffsetDateTime.class);
                    return row;
                },
                communityId, slot, mediaAssetId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean deleteByIdAndCommunity(long id, long communityId) {
        int rows = jdbc.update(
                "DELETE FROM specialization_branding_assets WHERE id = ? AND community_id = ?",
                id, communityId
        );
        return rows > 0;
    }

    public static class BrandingAssetRow {
        public long id;
        public long communityId;
        public long mediaAssetId;
        public String slot;
        public String s3Key;
        public String mimeType;
        public Integer width;
        public Integer height;
        public OffsetDateTime createdAt;
        public OffsetDateTime mediaCreatedAt;
    }
}
