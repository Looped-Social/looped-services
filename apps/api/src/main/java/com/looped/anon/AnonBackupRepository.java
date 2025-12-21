package com.looped.anon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AnonBackupRepository {
    private final JdbcTemplate jdbc;

    public AnonBackupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<BackupRow> MAPPER = new RowMapper<>() {
        @Override
        public BackupRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            BackupRow row = new BackupRow();
            row.blobId = rs.getObject("blob_id", UUID.class);
            row.salt = rs.getBytes("salt");
            row.ciphertext = rs.getBytes("ciphertext");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
            return row;
        }
    };

    public void upsert(UUID blobId, byte[] salt, byte[] ciphertext, OffsetDateTime expiresAt) {
        jdbc.update(
                "INSERT INTO anon_backup_blobs(blob_id, salt, ciphertext, expires_at) VALUES (?,?,?,?) " +
                        "ON CONFLICT (blob_id) DO UPDATE SET salt=EXCLUDED.salt, ciphertext=EXCLUDED.ciphertext, expires_at=EXCLUDED.expires_at",
                blobId, salt, ciphertext, expiresAt
        );
    }

    public Optional<BackupRow> find(UUID blobId) {
        var rows = jdbc.query(
                "SELECT blob_id, salt, ciphertext, created_at, expires_at FROM anon_backup_blobs WHERE blob_id = ?",
                MAPPER, blobId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public static class BackupRow {
        public UUID blobId;
        public byte[] salt;
        public byte[] ciphertext;
        public OffsetDateTime createdAt;
        public OffsetDateTime expiresAt;
    }
}
