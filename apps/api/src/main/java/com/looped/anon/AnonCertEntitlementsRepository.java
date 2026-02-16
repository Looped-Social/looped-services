package com.looped.anon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class AnonCertEntitlementsRepository {
    private final JdbcTemplate jdbc;

    public AnonCertEntitlementsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(byte[] certFingerprint, String anonCertKid, long userId, long communityId, OffsetDateTime certExpiresAt) {
        jdbc.update(
                "INSERT INTO anon_cert_entitlements(cert_fingerprint, anon_cert_kid, user_id, community_id, cert_expires_at) " +
                        "VALUES (?,?,?,?,?) " +
                        "ON CONFLICT (cert_fingerprint) DO UPDATE SET " +
                        "anon_cert_kid = EXCLUDED.anon_cert_kid, " +
                        "user_id = EXCLUDED.user_id, " +
                        "community_id = EXCLUDED.community_id, " +
                        "cert_expires_at = EXCLUDED.cert_expires_at, " +
                        "updated_at = now()",
                certFingerprint, anonCertKid, userId, communityId, certExpiresAt
        );
    }

    public Optional<Row> find(byte[] certFingerprint) {
        var rows = jdbc.query(
                "SELECT cert_fingerprint, anon_cert_kid, user_id, community_id, cert_expires_at " +
                        "FROM anon_cert_entitlements WHERE cert_fingerprint = ?",
                (rs, rowNum) -> new Row(
                        rs.getBytes("cert_fingerprint"),
                        rs.getString("anon_cert_kid"),
                        rs.getLong("user_id"),
                        rs.getLong("community_id"),
                        rs.getObject("cert_expires_at", OffsetDateTime.class)
                ),
                certFingerprint
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public record Row(byte[] certFingerprint, String anonCertKid, long userId, long communityId, OffsetDateTime certExpiresAt) {}
}
