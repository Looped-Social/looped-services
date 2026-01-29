package com.looped.media;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MediaRepository {
    private final JdbcTemplate jdbc;

    public MediaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(Long ownerId, String s3Key, String mimeType, Integer width, Integer height, Integer durationSeconds, Long thumbnailMediaAssetId) {
        return jdbc.query(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type, width, height, duration_seconds, thumbnail_media_asset_id) " +
                        "VALUES (?,?,?,?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                ownerId, s3Key, mimeType, width, height, durationSeconds, thumbnailMediaAssetId
        );
    }

    public boolean existsByKey(String s3Key) {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM media_assets WHERE s3_key=?", Integer.class, s3Key);
        return count != null && count > 0;
    }

    public java.util.Optional<MediaRow> findByKey(String s3Key) {
        var rows = jdbc.query(
                "SELECT id, owner_id, s3_key, mime_type, width, height, duration_seconds, thumbnail_media_asset_id, visibility, quarantined_at, quarantine_reason, removed_at, removed_by, removed_reason FROM media_assets WHERE s3_key = ?",
                (rs, rowNum) -> {
                    MediaRow row = new MediaRow();
                    row.id = rs.getLong("id");
                    row.ownerId = rs.getObject("owner_id", Long.class);
                    row.s3Key = rs.getString("s3_key");
                    row.mimeType = rs.getString("mime_type");
                    row.width = rs.getObject("width", Integer.class);
                    row.height = rs.getObject("height", Integer.class);
                    row.durationSeconds = rs.getObject("duration_seconds", Integer.class);
                    row.thumbnailMediaAssetId = rs.getObject("thumbnail_media_asset_id", Long.class);
                    row.visibility = rs.getString("visibility");
                    row.quarantinedAt = rs.getObject("quarantined_at", OffsetDateTime.class);
                    row.quarantineReason = rs.getString("quarantine_reason");
                    row.removedAt = rs.getObject("removed_at", OffsetDateTime.class);
                    row.removedBy = rs.getObject("removed_by", Long.class);
                    row.removedReason = rs.getString("removed_reason");
                    return row;
                },
                s3Key
        );
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    public Long findOwnerId(long mediaAssetId) {
        var rows = jdbc.query("SELECT owner_id FROM media_assets WHERE id = ?",
                (rs, rowNum) -> rs.getObject("owner_id", Long.class), mediaAssetId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Optional<MediaRow> findById(long mediaAssetId) {
        var rows = jdbc.query(
                "SELECT id, owner_id, s3_key, mime_type, width, height, duration_seconds, thumbnail_media_asset_id, visibility, quarantined_at, quarantine_reason, removed_at, removed_by, removed_reason FROM media_assets WHERE id = ?",
                (rs, rowNum) -> {
                    MediaRow row = new MediaRow();
                    row.id = rs.getLong("id");
                    row.ownerId = rs.getObject("owner_id", Long.class);
                    row.s3Key = rs.getString("s3_key");
                    row.mimeType = rs.getString("mime_type");
                    row.width = rs.getObject("width", Integer.class);
                    row.height = rs.getObject("height", Integer.class);
                    row.durationSeconds = rs.getObject("duration_seconds", Integer.class);
                    row.thumbnailMediaAssetId = rs.getObject("thumbnail_media_asset_id", Long.class);
                    row.visibility = rs.getString("visibility");
                    row.quarantinedAt = rs.getObject("quarantined_at", OffsetDateTime.class);
                    row.quarantineReason = rs.getString("quarantine_reason");
                    row.removedAt = rs.getObject("removed_at", OffsetDateTime.class);
                    row.removedBy = rs.getObject("removed_by", Long.class);
                    row.removedReason = rs.getString("removed_reason");
                    return row;
                },
                mediaAssetId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<MediaRow> findByIds(List<Long> mediaAssetIds) {
        if (mediaAssetIds == null || mediaAssetIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(mediaAssetIds.size(), "?"));
        return jdbc.query(
                "SELECT id, owner_id, s3_key, mime_type, width, height, duration_seconds, thumbnail_media_asset_id, visibility, quarantined_at, quarantine_reason, removed_at, removed_by, removed_reason " +
                        "FROM media_assets WHERE id IN (" + placeholders + ")",
                (rs, rowNum) -> {
                    MediaRow row = new MediaRow();
                    row.id = rs.getLong("id");
                    row.ownerId = rs.getObject("owner_id", Long.class);
                    row.s3Key = rs.getString("s3_key");
                    row.mimeType = rs.getString("mime_type");
                    row.width = rs.getObject("width", Integer.class);
                    row.height = rs.getObject("height", Integer.class);
                    row.durationSeconds = rs.getObject("duration_seconds", Integer.class);
                    row.thumbnailMediaAssetId = rs.getObject("thumbnail_media_asset_id", Long.class);
                    row.visibility = rs.getString("visibility");
                    row.quarantinedAt = rs.getObject("quarantined_at", OffsetDateTime.class);
                    row.quarantineReason = rs.getString("quarantine_reason");
                    row.removedAt = rs.getObject("removed_at", OffsetDateTime.class);
                    row.removedBy = rs.getObject("removed_by", Long.class);
                    row.removedReason = rs.getString("removed_reason");
                    return row;
                },
                mediaAssetIds.toArray()
        );
    }

    public boolean quarantine(long mediaAssetId, String reason) {
        int rows = jdbc.update(
                "UPDATE media_assets SET visibility = 'quarantined', quarantined_at = now(), quarantine_reason = ? " +
                        "WHERE id = ? AND removed_at IS NULL AND visibility <> 'quarantined'",
                reason, mediaAssetId
        );
        return rows > 0;
    }

    public boolean unquarantine(long mediaAssetId) {
        int rows = jdbc.update(
                "UPDATE media_assets SET visibility = 'public', quarantined_at = NULL, quarantine_reason = NULL " +
                        "WHERE id = ? AND removed_at IS NULL AND visibility <> 'public'",
                mediaAssetId
        );
        return rows > 0;
    }

    public boolean removeByAdmin(long mediaAssetId, long adminId, String reason) {
        int rows = jdbc.update(
                "UPDATE media_assets SET removed_at = now(), removed_by = ?, removed_reason = ? WHERE id = ? AND removed_at IS NULL",
                adminId, reason, mediaAssetId
        );
        return rows > 0;
    }

    public static class MediaRow {
        public long id;
        public Long ownerId;
        public String s3Key;
        public String mimeType;
        public Integer width;
        public Integer height;
        public Integer durationSeconds;
        public Long thumbnailMediaAssetId;
        public String visibility;
        public OffsetDateTime quarantinedAt;
        public String quarantineReason;
        public OffsetDateTime removedAt;
        public Long removedBy;
        public String removedReason;
    }
}
