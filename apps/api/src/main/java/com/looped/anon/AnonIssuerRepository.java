package com.looped.anon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class AnonIssuerRepository {
    private final JdbcTemplate jdbc;

    public AnonIssuerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IssuerRow> MAPPER = new RowMapper<>() {
        @Override
        public IssuerRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            IssuerRow row = new IssuerRow();
            row.id = rs.getLong("id");
            row.kid = rs.getString("kid");
            row.alg = rs.getString("alg");
            row.publicKey = rs.getBytes("public_key");
            row.scopeKind = rs.getString("scope_kind");
            long scopeId = rs.getLong("scope_id");
            row.scopeId = rs.wasNull() ? null : scopeId;
            row.expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.rotatedAt = rs.getObject("rotated_at", OffsetDateTime.class);
            return row;
        }
    };

    public Optional<IssuerRow> findByKid(String kid) {
        var rows = jdbc.query(
                "SELECT id, kid, alg, public_key, scope_kind, scope_id, expires_at, created_at, rotated_at " +
                        "FROM anon_issuers WHERE kid = ?",
                MAPPER, kid
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void upsert(String kid, String alg, byte[] publicKey, String scopeKind, Long scopeId, OffsetDateTime expiresAt) {
        jdbc.update(
                "INSERT INTO anon_issuers(kid, alg, public_key, scope_kind, scope_id, expires_at) VALUES (?,?,?,?,?,?) " +
                        "ON CONFLICT (kid) DO UPDATE SET alg=EXCLUDED.alg, public_key=EXCLUDED.public_key, " +
                        "scope_kind=EXCLUDED.scope_kind, scope_id=EXCLUDED.scope_id, expires_at=EXCLUDED.expires_at",
                kid, alg, publicKey, scopeKind, scopeId, expiresAt
        );
    }

    public static class IssuerRow {
        public long id;
        public String kid;
        public String alg;
        public byte[] publicKey;
        public String scopeKind;
        public Long scopeId;
        public OffsetDateTime expiresAt;
        public OffsetDateTime createdAt;
        public OffsetDateTime rotatedAt;
    }
}
