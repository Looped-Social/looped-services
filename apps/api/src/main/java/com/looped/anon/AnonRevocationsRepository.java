package com.looped.anon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnonRevocationsRepository {
    private final JdbcTemplate jdbc;

    public AnonRevocationsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isRevokedByPubkey(byte[] pubkey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM anon_revocations WHERE persona_pubkey = ?",
                Integer.class, pubkey
        );
        return count != null && count > 0;
    }

    public boolean revokeByPubkey(byte[] pubkey, String reason) {
        int rows = jdbc.update(
                "INSERT INTO anon_revocations(persona_pubkey, reason) " +
                        "SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM anon_revocations WHERE persona_pubkey = ?)",
                pubkey, reason, pubkey
        );
        return rows > 0;
    }
}
