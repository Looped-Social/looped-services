package com.looped.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AppSettingsRepository {
    private final JdbcTemplate jdbc;

    public AppSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> findLong(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        var rows = jdbc.query(
                "SELECT value_int FROM app_settings WHERE key = ?",
                (rs, rowNum) -> {
                    Long val = rs.getLong("value_int");
                    return rs.wasNull() ? null : val;
                },
                key
        );
        if (rows.isEmpty()) return Optional.empty();
        return Optional.ofNullable(rows.get(0));
    }

    public Optional<String> findString(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        var rows = jdbc.query(
                "SELECT value_text FROM app_settings WHERE key = ?",
                (rs, rowNum) -> rs.getString("value_text"),
                key
        );
        if (rows.isEmpty()) return Optional.empty();
        String val = rows.get(0);
        if (val == null) return Optional.empty();
        String trimmed = val.trim();
        if (trimmed.isBlank()) return Optional.empty();
        return Optional.of(trimmed);
    }

    public void upsertLong(String key, long value, Long updatedByAdminId) {
        jdbc.update(
                "INSERT INTO app_settings(key, value_int, updated_at, updated_by_admin_id) VALUES (?,?, now(), ?) " +
                        "ON CONFLICT (key) DO UPDATE SET value_int = EXCLUDED.value_int, updated_at = now(), updated_by_admin_id = EXCLUDED.updated_by_admin_id",
                key, value, updatedByAdminId
        );
    }

    public void upsertString(String key, String value, Long updatedByAdminId) {
        jdbc.update(
                "INSERT INTO app_settings(key, value_text, updated_at, updated_by_admin_id) VALUES (?,?, now(), ?) " +
                        "ON CONFLICT (key) DO UPDATE SET value_text = EXCLUDED.value_text, updated_at = now(), updated_by_admin_id = EXCLUDED.updated_by_admin_id",
                key, value, updatedByAdminId
        );
    }
}
