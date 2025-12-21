package com.looped.principals;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class PrincipalRepository {
    private final JdbcTemplate jdbc;

    public PrincipalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PrincipalRow> MAPPER = new RowMapper<>() {
        @Override
        public PrincipalRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            PrincipalRow row = new PrincipalRow();
            row.id = rs.getLong("id");
            row.kind = rs.getString("kind");
            long userId = rs.getLong("user_id");
            row.userId = rs.wasNull() ? null : userId;
            long anonProfileId = rs.getLong("anon_profile_id");
            row.anonProfileId = rs.wasNull() ? null : anonProfileId;
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public Optional<PrincipalRow> findByUserId(long userId) {
        var rows = jdbc.query(
                "SELECT id, kind, user_id, anon_profile_id, created_at FROM principals WHERE user_id = ?",
                MAPPER, userId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<PrincipalRow> findByAnonProfileId(long anonProfileId) {
        var rows = jdbc.query(
                "SELECT id, kind, user_id, anon_profile_id, created_at FROM principals WHERE anon_profile_id = ?",
                MAPPER, anonProfileId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<PrincipalRow> findById(long id) {
        var rows = jdbc.query(
                "SELECT id, kind, user_id, anon_profile_id, created_at FROM principals WHERE id = ?",
                MAPPER, id
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public PrincipalRow createForAnon(long anonProfileId) {
        Long id = jdbc.query(
                "INSERT INTO principals(kind, anon_profile_id) VALUES ('anon', ?) ON CONFLICT (anon_profile_id) DO NOTHING RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                anonProfileId
        );
        if (id == null) {
            return findByAnonProfileId(anonProfileId).orElseThrow();
        }
        return findById(id).orElseThrow();
    }

    public PrincipalRow createForUser(long userId) {
        Long id = jdbc.query(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) ON CONFLICT (user_id) DO NOTHING RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId
        );
        if (id == null) {
            return findByUserId(userId).orElseThrow();
        }
        return findById(id).orElseThrow();
    }

    public static class PrincipalRow {
        public long id;
        public String kind;
        public Long userId;
        public Long anonProfileId;
        public OffsetDateTime createdAt;
    }
}
