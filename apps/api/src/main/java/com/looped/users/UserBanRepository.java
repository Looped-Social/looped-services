package com.looped.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class UserBanRepository {
    private final JdbcTemplate jdbc;

    public UserBanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<BanRow> MAPPER = new RowMapper<>() {
        @Override
        public BanRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            BanRow row = new BanRow();
            row.id = rs.getLong("id");
            row.userId = rs.getLong("user_id");
            row.reason = rs.getString("reason");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
            row.revokedAt = rs.getObject("revoked_at", OffsetDateTime.class);
            long createdBy = rs.getLong("created_by");
            row.createdBy = rs.wasNull() ? null : createdBy;
            long revokedBy = rs.getLong("revoked_by");
            row.revokedBy = rs.wasNull() ? null : revokedBy;
            return row;
        }
    };

    public Optional<BanRow> findActiveByUserId(long userId) {
        var list = jdbc.query(
                "SELECT * FROM user_bans WHERE user_id = ? AND revoked_at IS NULL " +
                        "AND (expires_at IS NULL OR expires_at > now()) " +
                        "ORDER BY created_at DESC LIMIT 1",
                MAPPER, userId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<BanRow> findActiveByFirebaseUid(String firebaseUid) {
        var list = jdbc.query(
                "SELECT b.* FROM user_bans b " +
                        "JOIN users u ON u.id = b.user_id " +
                        "WHERE u.firebase_uid = ? AND b.revoked_at IS NULL " +
                        "AND (b.expires_at IS NULL OR b.expires_at > now()) " +
                        "ORDER BY b.created_at DESC LIMIT 1",
                MAPPER, firebaseUid
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public long banUser(long userId, Long createdBy, String reason, OffsetDateTime expiresAt) {
        Long id = jdbc.query(
                "INSERT INTO user_bans(user_id, reason, created_by, expires_at) VALUES (?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId, reason, createdBy, expiresAt
        );
        if (id == null) {
            throw new IllegalStateException("Failed to insert user ban");
        }
        return id;
    }

    public int revokeActive(long userId, Long revokedBy) {
        return jdbc.update(
                "UPDATE user_bans SET revoked_at = now(), revoked_by = ? " +
                        "WHERE user_id = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())",
                revokedBy, userId
        );
    }

    public static class BanRow {
        public long id;
        public long userId;
        public String reason;
        public OffsetDateTime createdAt;
        public OffsetDateTime expiresAt;
        public OffsetDateTime revokedAt;
        public Long createdBy;
        public Long revokedBy;
    }
}
