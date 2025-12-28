package com.looped.notifications;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

@Repository
public class NotificationPreferencesRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public NotificationPreferencesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Map<String, Object>> findByPrincipalId(long principalId) {
        var rows = jdbc.query(
                "SELECT notifications FROM principal_settings WHERE principal_id = ?",
                (rs, rowNum) -> rs.getString("notifications"),
                principalId
        );
        if (rows.isEmpty() || rows.get(0) == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(rows.get(0), MAP_TYPE));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void upsert(long principalId, Map<String, Object> notifications) {
        String json;
        try {
            json = mapper.writeValueAsString(notifications);
        } catch (Exception e) {
            json = "{}";
        }
        jdbc.update(
                "INSERT INTO principal_settings(principal_id, notifications, updated_at) VALUES (?, ?::jsonb, now()) " +
                        "ON CONFLICT (principal_id) DO UPDATE SET notifications = EXCLUDED.notifications, updated_at = now()",
                principalId, json
        );
    }
}
