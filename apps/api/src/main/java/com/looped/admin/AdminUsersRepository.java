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
public class AdminUsersRepository {
    private final JdbcTemplate jdbc;

    public AdminUsersRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AdminUserRow> MAPPER = new RowMapper<>() {
        @Override
        public AdminUserRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            AdminUserRow row = new AdminUserRow();
            row.id = rs.getLong("id");
            row.firebaseUid = rs.getString("firebase_uid");
            row.email = rs.getString("email");
            row.role = rs.getString("role");
            row.status = rs.getString("status");
            row.permissions = readPermissions(rs);
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
            row.lastLoginAt = rs.getObject("last_login_at", OffsetDateTime.class);
            return row;
        }
    };

    public Optional<AdminUserRow> findActiveByFirebaseUid(String firebaseUid) {
        var list = jdbc.query(
                "SELECT * FROM admin_users WHERE firebase_uid = ? AND status = 'active' LIMIT 1",
                MAPPER, firebaseUid
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<AdminUserRow> findByEmail(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) return Optional.empty();
        var list = jdbc.query(
                "SELECT * FROM admin_users WHERE email = ? LIMIT 1",
                MAPPER, normalized
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<AdminUserRow> findById(long id) {
        var list = jdbc.query(
                "SELECT * FROM admin_users WHERE id = ? LIMIT 1",
                MAPPER, id
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<AdminUserRow> listAll() {
        return jdbc.query(
                "SELECT * FROM admin_users ORDER BY created_at DESC, id DESC",
                MAPPER
        );
    }

    public long insert(String firebaseUid, String email, String role, String status, List<String> permissions) {
        Long id = jdbc.query(con -> {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO admin_users(firebase_uid, email, role, status, permissions) " +
                                    "VALUES (?, ?, ?, ?, ?) RETURNING id"
                    );
                    ps.setString(1, firebaseUid);
                    ps.setString(2, normalizeEmail(email));
                    ps.setString(3, role);
                    ps.setString(4, status);
                    ps.setArray(5, toTextArray(con, permissions));
                    return ps;
                },
                rs -> rs.next() ? rs.getLong(1) : null
        );
        if (id == null) {
            throw new IllegalStateException("Failed to insert admin user");
        }
        return id;
    }

    public Optional<AdminUserRow> claimActiveByEmail(String email, String firebaseUid) {
        String normalized = normalizeEmail(email);
        if (normalized == null) return Optional.empty();
        var list = jdbc.query(
                "UPDATE admin_users SET firebase_uid = ?, updated_at = now(), last_login_at = now() " +
                        "WHERE email = ? AND firebase_uid IS NULL AND status = 'active' " +
                        "RETURNING *",
                MAPPER, firebaseUid, normalized
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public boolean update(long id, String role, String status, List<String> permissions) {
        int rows = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "UPDATE admin_users SET role = ?, status = ?, permissions = ?, updated_at = now() WHERE id = ?"
            );
            ps.setString(1, role);
            ps.setString(2, status);
            ps.setArray(3, toTextArray(con, permissions));
            ps.setLong(4, id);
            return ps;
        });
        return rows > 0;
    }

    private String normalizeEmail(String email) {
        if (email == null) return null;
        String trimmed = email.trim();
        if (trimmed.isBlank()) return null;
        return trimmed.toLowerCase(Locale.ROOT);
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

    public static class AdminUserRow {
        public long id;
        public String firebaseUid;
        public String email;
        public String role;
        public String status;
        public List<String> permissions;
        public OffsetDateTime createdAt;
        public OffsetDateTime updatedAt;
        public OffsetDateTime lastLoginAt;
    }
}
