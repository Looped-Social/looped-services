package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class SpecializationLimitsRepository {
    private final JdbcTemplate jdbc;

    public SpecializationLimitsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OffsetDateTime> findLastChange(long userId, String specializationType, String scope) {
        var rows = jdbc.query(
                "SELECT last_changed_at FROM user_specialization_limits " +
                        "WHERE user_id = ? AND specialization_type = ? AND scope = ?",
                (rs, rowNum) -> rs.getObject("last_changed_at", OffsetDateTime.class),
                userId, specializationType, scope
        );
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    public void upsertLastChange(long userId, String specializationType, String scope, OffsetDateTime at) {
        jdbc.update(
                "INSERT INTO user_specialization_limits(user_id, specialization_type, scope, last_changed_at) " +
                        "VALUES (?,?,?,?) ON CONFLICT (user_id, specialization_type, scope) " +
                        "DO UPDATE SET last_changed_at = EXCLUDED.last_changed_at",
                userId, specializationType, scope, at
        );
    }
}
