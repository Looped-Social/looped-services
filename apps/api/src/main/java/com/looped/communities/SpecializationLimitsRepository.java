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

    public Optional<LimitRow> findLastChangeWithCooldownMonths(long userId, String specializationType, String scope) {
        var rows = jdbc.query(
                "SELECT last_changed_at, cooldown_months FROM user_specialization_limits " +
                        "WHERE user_id = ? AND specialization_type = ? AND scope = ?",
                (rs, rowNum) -> {
                    OffsetDateTime at = rs.getObject("last_changed_at", OffsetDateTime.class);
                    int months = rs.getInt("cooldown_months");
                    Integer cooldownMonths = rs.wasNull() ? null : months;
                    return new LimitRow(at, cooldownMonths);
                },
                userId, specializationType, scope
        );
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    public void upsertLastChange(long userId, String specializationType, String scope, OffsetDateTime at) {
        upsertLastChangeWithCooldownMonths(userId, specializationType, scope, at, null);
    }

    public void upsertLastChangeWithCooldownMonths(long userId, String specializationType, String scope, OffsetDateTime at, Integer cooldownMonths) {
        jdbc.update(
                "INSERT INTO user_specialization_limits(user_id, specialization_type, scope, last_changed_at, cooldown_months) " +
                        "VALUES (?,?,?,?,?) ON CONFLICT (user_id, specialization_type, scope) " +
                        "DO UPDATE SET last_changed_at = EXCLUDED.last_changed_at, cooldown_months = EXCLUDED.cooldown_months",
                userId, specializationType, scope, at, cooldownMonths
        );
    }

    public boolean deleteLastChange(long userId, String specializationType, String scope) {
        int rows = jdbc.update(
                "DELETE FROM user_specialization_limits WHERE user_id = ? AND specialization_type = ? AND scope = ?",
                userId, specializationType, scope
        );
        return rows > 0;
    }

    public record LimitRow(OffsetDateTime lastChangedAt, Integer cooldownMonths) {}
}
