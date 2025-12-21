package com.looped.anon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class AnonymousProfilesRepository {
    private final JdbcTemplate jdbc;

    public AnonymousProfilesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AnonymousProfileRow> MAPPER = new RowMapper<>() {
        @Override
        public AnonymousProfileRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            AnonymousProfileRow row = new AnonymousProfileRow();
            row.id = rs.getLong("id");
            row.companyId = rs.getLong("company_id");
            row.publicKey = rs.getBytes("public_key");
            row.handle = rs.getString("handle");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public Optional<AnonymousProfileRow> findById(long id) {
        var rows = jdbc.query(
                "SELECT id, company_id, public_key, handle, created_at FROM anonymous_profiles WHERE id = ?",
                MAPPER, id
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<AnonymousProfileRow> findByPublicKey(byte[] publicKey) {
        var rows = jdbc.query(
                "SELECT id, company_id, public_key, handle, created_at FROM anonymous_profiles WHERE public_key = ?",
                MAPPER, publicKey
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public AnonymousProfileRow create(long companyId, byte[] publicKey, String handle) {
        Long id = jdbc.query(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                companyId, publicKey, handle
        );
        return findById(id).orElseThrow();
    }

    public String nextHandle(long companyId) {
        Long next = jdbc.queryForObject(
                "INSERT INTO anon_handle_counters(company_id, next_value) VALUES (?, 1) " +
                        "ON CONFLICT (company_id) DO UPDATE SET next_value = anon_handle_counters.next_value + 1 " +
                        "RETURNING next_value",
                Long.class,
                companyId
        );
        return "anonymous" + next;
    }

    public static class AnonymousProfileRow {
        public long id;
        public long companyId;
        public byte[] publicKey;
        public String handle;
        public OffsetDateTime createdAt;
    }
}
