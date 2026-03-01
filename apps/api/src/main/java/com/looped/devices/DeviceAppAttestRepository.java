package com.looped.devices;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class DeviceAppAttestRepository {
    private final JdbcTemplate jdbcTemplate;

    public DeviceAppAttestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Row upsert(long userId,
                      String keyId,
                      String platform,
                      String status,
                      OffsetDateTime lastChallengeAt,
                      OffsetDateTime lastVerifiedAt,
                      OffsetDateTime trustedUntil,
                      String lastError) {
        Long id = jdbcTemplate.query(
                "INSERT INTO device_app_attest_keys(user_id, key_id, platform, status, last_challenge_at, last_verified_at, trusted_until, last_seen_at, last_error, updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?, now()) " +
                        "ON CONFLICT (key_id) DO UPDATE SET " +
                        "user_id = EXCLUDED.user_id, " +
                        "platform = EXCLUDED.platform, " +
                        "status = EXCLUDED.status, " +
                        "last_challenge_at = COALESCE(EXCLUDED.last_challenge_at, device_app_attest_keys.last_challenge_at), " +
                        "last_verified_at = COALESCE(EXCLUDED.last_verified_at, device_app_attest_keys.last_verified_at), " +
                        "trusted_until = EXCLUDED.trusted_until, " +
                        "last_seen_at = EXCLUDED.last_seen_at, " +
                        "last_error = EXCLUDED.last_error, " +
                        "updated_at = now() " +
                        "RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId,
                keyId,
                platform,
                status,
                lastChallengeAt,
                lastVerifiedAt,
                trustedUntil,
                OffsetDateTime.now(),
                lastError
        );
        if (id == null) {
            throw new IllegalStateException("Failed to upsert App Attest key");
        }
        return findById(id).orElseThrow();
    }

    public Optional<Row> findByUserIdAndKeyId(long userId, String keyId) {
        List<Row> rows = jdbcTemplate.query(
                "SELECT id, user_id, key_id, platform, status, last_challenge_at, last_verified_at, trusted_until, last_seen_at, last_error, created_at, updated_at " +
                        "FROM device_app_attest_keys WHERE user_id = ? AND key_id = ? LIMIT 1",
                (rs, rowNum) -> map(rs),
                userId,
                keyId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<Row> findLatestByUserId(long userId) {
        List<Row> rows = jdbcTemplate.query(
                "SELECT id, user_id, key_id, platform, status, last_challenge_at, last_verified_at, trusted_until, last_seen_at, last_error, created_at, updated_at " +
                        "FROM device_app_attest_keys WHERE user_id = ? ORDER BY updated_at DESC, id DESC LIMIT 1",
                (rs, rowNum) -> map(rs),
                userId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean hasActiveTrustedKey(long userId, String keyId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_app_attest_keys " +
                        "WHERE user_id = ? AND key_id = ? AND status = 'trusted' " +
                        "AND (trusted_until IS NULL OR trusted_until > now())",
                Integer.class,
                userId,
                keyId
        );
        return count != null && count > 0;
    }

    private Optional<Row> findById(long id) {
        List<Row> rows = jdbcTemplate.query(
                "SELECT id, user_id, key_id, platform, status, last_challenge_at, last_verified_at, trusted_until, last_seen_at, last_error, created_at, updated_at " +
                        "FROM device_app_attest_keys WHERE id = ? LIMIT 1",
                (rs, rowNum) -> map(rs),
                id
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Row map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Row row = new Row();
        row.id = rs.getLong("id");
        row.userId = rs.getLong("user_id");
        row.keyId = rs.getString("key_id");
        row.platform = rs.getString("platform");
        row.status = rs.getString("status");
        row.lastChallengeAt = rs.getObject("last_challenge_at", OffsetDateTime.class);
        row.lastVerifiedAt = rs.getObject("last_verified_at", OffsetDateTime.class);
        row.trustedUntil = rs.getObject("trusted_until", OffsetDateTime.class);
        row.lastSeenAt = rs.getObject("last_seen_at", OffsetDateTime.class);
        row.lastError = rs.getString("last_error");
        row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
        row.updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
        return row;
    }

    public static class Row {
        public long id;
        public long userId;
        public String keyId;
        public String platform;
        public String status;
        public OffsetDateTime lastChallengeAt;
        public OffsetDateTime lastVerifiedAt;
        public OffsetDateTime trustedUntil;
        public OffsetDateTime lastSeenAt;
        public String lastError;
        public OffsetDateTime createdAt;
        public OffsetDateTime updatedAt;
    }
}
