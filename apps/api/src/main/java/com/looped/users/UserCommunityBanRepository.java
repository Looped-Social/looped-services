package com.looped.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class UserCommunityBanRepository {
    private final JdbcTemplate jdbc;

    public UserCommunityBanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<BanRow> MAPPER = new RowMapper<>() {
        @Override
        public BanRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            BanRow row = new BanRow();
            row.id = rs.getLong("id");
            row.userId = rs.getLong("user_id");
            row.scope = rs.getString("scope");
            long communityId = rs.getLong("community_id");
            row.communityId = rs.wasNull() ? null : communityId;
            row.communityName = rs.getString("community_name");
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

    public boolean isBanned(long userId, Long communityId) {
        if (communityId == null) return false;
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (" +
                        "SELECT 1 FROM user_community_bans b " +
                        "WHERE b.user_id = ? AND b.revoked_at IS NULL AND (b.expires_at IS NULL OR b.expires_at > now()) " +
                        "AND (b.scope = 'all_communities' OR (b.scope = 'community' AND b.community_id = ?))" +
                        ")",
                Boolean.class,
                userId, communityId
        );
        return Boolean.TRUE.equals(exists);
    }

    public Optional<BanRow> findActiveForUserAndCommunity(long userId, long communityId) {
        var list = jdbc.query(
                "SELECT b.*, c.name AS community_name FROM user_community_bans b " +
                        "LEFT JOIN communities c ON c.id = b.community_id " +
                        "WHERE b.user_id = ? AND b.revoked_at IS NULL AND (b.expires_at IS NULL OR b.expires_at > now()) " +
                        "AND (b.scope = 'all_communities' OR (b.scope = 'community' AND b.community_id = ?)) " +
                        "ORDER BY b.created_at DESC, b.id DESC LIMIT 1",
                MAPPER, userId, communityId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<BanRow> listForUser(long userId, boolean activeOnly) {
        String where = activeOnly
                ? "WHERE b.user_id = ? AND b.revoked_at IS NULL AND (b.expires_at IS NULL OR b.expires_at > now()) "
                : "WHERE b.user_id = ? ";
        return jdbc.query(
                "SELECT b.*, c.name AS community_name FROM user_community_bans b " +
                        "LEFT JOIN communities c ON c.id = b.community_id " +
                        where +
                        "ORDER BY b.created_at DESC, b.id DESC",
                MAPPER, userId
        );
    }

    public long banAllCommunities(long userId, Long createdBy, String reason, OffsetDateTime expiresAt) {
        Long id = jdbc.query(
                "INSERT INTO user_community_bans(user_id, scope, community_id, reason, created_by, expires_at) " +
                        "VALUES (?, 'all_communities', NULL, ?, ?, ?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId, reason, createdBy, expiresAt
        );
        if (id == null) throw new IllegalStateException("Failed to insert user community ban");
        return id;
    }

    public long banCommunity(long userId, long communityId, Long createdBy, String reason, OffsetDateTime expiresAt) {
        Long id = jdbc.query(
                "INSERT INTO user_community_bans(user_id, scope, community_id, reason, created_by, expires_at) " +
                        "VALUES (?, 'community', ?, ?, ?, ?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId, communityId, reason, createdBy, expiresAt
        );
        if (id == null) throw new IllegalStateException("Failed to insert user community ban");
        return id;
    }

    public boolean revoke(long banId, Long revokedBy) {
        int rows = jdbc.update(
                "UPDATE user_community_bans SET revoked_at = now(), revoked_by = ? WHERE id = ? AND revoked_at IS NULL",
                revokedBy, banId
        );
        return rows > 0;
    }

    public boolean revokeForUser(long banId, long userId, Long revokedBy) {
        int rows = jdbc.update(
                "UPDATE user_community_bans SET revoked_at = now(), revoked_by = ? " +
                        "WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
                revokedBy, banId, userId
        );
        return rows > 0;
    }

    public static class BanRow {
        public long id;
        public long userId;
        public String scope; // community|all_communities
        public Long communityId;
        public String communityName;
        public String reason;
        public OffsetDateTime createdAt;
        public OffsetDateTime expiresAt;
        public OffsetDateTime revokedAt;
        public Long createdBy;
        public Long revokedBy;
    }
}
