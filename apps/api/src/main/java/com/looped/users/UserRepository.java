package com.looped.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<UserRow> MAPPER = new RowMapper<>() {
        @Override
        public UserRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            UserRow row = new UserRow();
            row.id = rs.getLong("id");
            row.firebaseUid = rs.getString("firebase_uid");
            row.handle = rs.getString("handle");
            long company = rs.getLong("company_id");
            row.companyId = rs.wasNull() ? null : company;
            return row;
        }
    };

    public Optional<UserRow> findByFirebaseUid(String firebaseUid) {
        var list = jdbcTemplate.query("SELECT id, firebase_uid, handle, company_id FROM users WHERE firebase_uid = ?", MAPPER, firebaseUid);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public static class UserRow {
        public Long id;
        public String firebaseUid;
        public String handle;
        public Long companyId;
    }
}

