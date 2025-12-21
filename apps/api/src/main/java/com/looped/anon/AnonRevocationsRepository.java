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
}
