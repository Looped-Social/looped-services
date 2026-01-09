package com.looped.media;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class MediaRepository {
    private final JdbcTemplate jdbc;

    public MediaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(Long ownerId, String s3Key, String mimeType, Integer width, Integer height, Integer durationSeconds) {
        return jdbc.query(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type, width, height, duration_seconds) " +
                        "VALUES (?,?,?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                ownerId, s3Key, mimeType, width, height, durationSeconds
        );
    }

    public boolean existsByKey(String s3Key) {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM media_assets WHERE s3_key=?", Integer.class, s3Key);
        return count != null && count > 0;
    }

    public java.util.Optional<MediaRow> findByKey(String s3Key) {
        var rows = jdbc.query(
                "SELECT id, owner_id, s3_key, mime_type FROM media_assets WHERE s3_key = ?",
                (rs, rowNum) -> {
                    MediaRow row = new MediaRow();
                    row.id = rs.getLong("id");
                    row.ownerId = rs.getObject("owner_id", Long.class);
                    row.s3Key = rs.getString("s3_key");
                    row.mimeType = rs.getString("mime_type");
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
                "SELECT id, owner_id, s3_key, mime_type FROM media_assets WHERE id = ?",
                (rs, rowNum) -> {
                    MediaRow row = new MediaRow();
                    row.id = rs.getLong("id");
                    row.ownerId = rs.getObject("owner_id", Long.class);
                    row.s3Key = rs.getString("s3_key");
                    row.mimeType = rs.getString("mime_type");
                    return row;
                },
                mediaAssetId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public static class MediaRow {
        public long id;
        public Long ownerId;
        public String s3Key;
        public String mimeType;
    }
}
