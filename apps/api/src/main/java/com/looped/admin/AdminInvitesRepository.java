package com.looped.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class AdminInvitesRepository {
    private final JdbcTemplate jdbc;

    public AdminInvitesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AdminInviteRow> MAPPER = new RowMapper<>() {
        @Override
        public AdminInviteRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            AdminInviteRow row = new AdminInviteRow();
            row.id = rs.getLong("id");
            row.email = rs.getString("email");
            row.role = rs.getString("role");
            row.permissions = readPermissions(rs);
            row.status = rs.getString("status");
            row.tokenHash = rs.getString("token_hash");
            row.createdBy = rs.getObject("created_by", Long.class);
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
            row.acceptedAt = rs.getObject("accepted_at", OffsetDateTime.class);
            row.acceptedBy = rs.getObject("accepted_by", Long.class);
            return row;
        }
    };

    public Optional<AdminInviteRow> findPendingByEmail(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) return Optional.empty();
        expirePendingByEmail(normalized);
        var list = jdbc.query(
                "SELECT * FROM admin_invites WHERE email = ? AND status = 'pending' LIMIT 1",
                MAPPER, normalized
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<AdminInviteRow> findPendingByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) return Optional.empty();
        expirePendingByTokenHash(tokenHash);
        var list = jdbc.query(
                "SELECT * FROM admin_invites WHERE token_hash = ? AND status = 'pending' AND expires_at > now() LIMIT 1",
                MAPPER, tokenHash
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public AdminInviteRow insert(String email, String role, List<String> permissions, String tokenHash,
                                 OffsetDateTime expiresAt, Long createdBy) {
        AdminInviteRow row = jdbc.query(con -> {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO admin_invites(email, role, permissions, token_hash, expires_at, created_by) " +
                                    "VALUES (?, ?, ?, ?, ?, ?) RETURNING *"
                    );
                    ps.setString(1, normalizeEmail(email));
                    ps.setString(2, role);
                    ps.setArray(3, toTextArray(con, permissions));
                    ps.setString(4, tokenHash);
                    ps.setObject(5, expiresAt);
                    ps.setObject(6, createdBy);
                    return ps;
                },
                rs -> rs.next() ? MAPPER.mapRow(rs, 0) : null
        );
        if (row == null) {
            throw new IllegalStateException("Failed to insert admin invite");
        }
        return row;
    }

    public boolean markAccepted(long id, long acceptedBy) {
        int rows = jdbc.update(
                "UPDATE admin_invites SET status = 'accepted', accepted_at = now(), accepted_by = ? WHERE id = ?",
                acceptedBy, id
        );
        return rows > 0;
    }

    private void expirePendingByEmail(String email) {
        jdbc.update(
                "UPDATE admin_invites SET status = 'expired' WHERE email = ? AND status = 'pending' AND expires_at <= now()",
                email
        );
    }

    private void expirePendingByTokenHash(String tokenHash) {
        jdbc.update(
                "UPDATE admin_invites SET status = 'expired' WHERE token_hash = ? AND status = 'pending' AND expires_at <= now()",
                tokenHash
        );
    }

    private static List<String> readPermissions(ResultSet rs) throws SQLException {
        Array array = rs.getArray("permissions");
        if (array == null) return List.of();
        String[] perms = (String[]) array.getArray();
        if (perms == null || perms.length == 0) return List.of();
        return List.of(perms);
    }

    private Array toTextArray(java.sql.Connection con, List<String> permissions) throws SQLException {
        String[] perms = permissions == null ? new String[0] : permissions.toArray(new String[0]);
        return con.createArrayOf("text", perms);
    }

    private String normalizeEmail(String email) {
        if (email == null) return null;
        String trimmed = email.trim();
        if (trimmed.isBlank()) return null;
        return trimmed.toLowerCase(Locale.ROOT);
    }

    public static class AdminInviteRow {
        public long id;
        public String email;
        public String role;
        public List<String> permissions;
        public String tokenHash;
        public String status;
        public Long createdBy;
        public OffsetDateTime createdAt;
        public OffsetDateTime expiresAt;
        public OffsetDateTime acceptedAt;
        public Long acceptedBy;
    }
}
