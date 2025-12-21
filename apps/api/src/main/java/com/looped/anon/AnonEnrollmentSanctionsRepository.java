package com.looped.anon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnonEnrollmentSanctionsRepository {
    private final JdbcTemplate jdbc;

    public AnonEnrollmentSanctionsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsActive(long userId, String scopeKind, Long scopeId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM anon_enrollment_sanctions " +
                        "WHERE user_id = ? AND scope_kind = ? AND " +
                        "((? IS NULL AND scope_id IS NULL) OR scope_id = ?) AND status = 'active'",
                Integer.class, userId, scopeKind, scopeId, scopeId
        );
        return count != null && count > 0;
    }

    public void addActive(long userId, String scopeKind, Long scopeId, String reason) {
        jdbc.update(
                "INSERT INTO anon_enrollment_sanctions(user_id, scope_kind, scope_id, status, reason) " +
                        "VALUES (?,?,?, 'active', ?)",
                userId, scopeKind, scopeId, reason
        );
    }
}
