package com.looped.notifications;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class NotificationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public NotificationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy();
    }

    private final RowMapper<NotificationRow> mapperRow = new RowMapper<>() {
        @Override
        public NotificationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            NotificationRow row = new NotificationRow();
            row.id = rs.getLong("id");
            row.userId = rs.getLong("user_id");
            row.type = rs.getString("type");
            String payloadJson = rs.getString("payload");
            try {
                row.payload = payloadJson == null ? Map.of() : mapper.readValue(payloadJson, MAP_TYPE);
            } catch (Exception e) {
                row.payload = Map.of();
            }
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.readAt = rs.getObject("read_at", OffsetDateTime.class);
            row.dismissedAt = rs.getObject("dismissed_at", OffsetDateTime.class);
            return row;
        }
    };

    public List<NotificationRow> findByUser(long userId, OffsetDateTime cursorTs, Long cursorId, int limit, boolean includeDismissed) {
        String dismissedFilter = includeDismissed ? "" : " AND dismissed_at IS NULL";
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT id, user_id, type, payload, created_at, read_at, dismissed_at FROM notifications " +
                            "WHERE user_id = ?" + dismissedFilter + " ORDER BY created_at DESC, id DESC LIMIT ?",
                    mapperRow, userId, limit
            );
        }
        return jdbc.query(
                "SELECT id, user_id, type, payload, created_at, read_at, dismissed_at FROM notifications " +
                        "WHERE user_id = ? AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                        dismissedFilter +
                        " ORDER BY created_at DESC, id DESC LIMIT ?",
                mapperRow, userId, cursorTs, cursorTs, cursorId, limit
        );
    }

    public List<NotificationRow> findByUser(long userId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        return findByUser(userId, cursorTs, cursorId, limit, false);
    }

    public Optional<NotificationRow> findById(long id) {
        var list = jdbc.query("SELECT id, user_id, type, payload, created_at, read_at, dismissed_at FROM notifications WHERE id = ?", mapperRow, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public boolean markRead(long notificationId, long userId, OffsetDateTime ts) {
        int updated = jdbc.update("UPDATE notifications SET read_at = ? WHERE id = ? AND user_id = ?", ts, notificationId, userId);
        return updated > 0;
    }

    public boolean markDismissed(long notificationId, long userId, OffsetDateTime ts) {
        int updated = jdbc.update(
                "UPDATE notifications SET dismissed_at = COALESCE(dismissed_at, ?) WHERE id = ? AND user_id = ?",
                ts,
                notificationId,
                userId
        );
        return updated > 0;
    }

    public int markDismissedByIds(long userId, List<Long> notificationIds, OffsetDateTime ts) {
        if (notificationIds == null || notificationIds.isEmpty()) return 0;
        return jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "UPDATE notifications SET dismissed_at = ? " +
                            "WHERE user_id = ? AND dismissed_at IS NULL AND id = ANY (?::bigint[])"
            );
            ps.setObject(1, ts);
            ps.setLong(2, userId);
            ps.setArray(3, connection.createArrayOf("bigint", notificationIds.toArray()));
            return ps;
        });
    }

    public long insert(long userId, String type, Map<String, Object> payload) {
        String payloadJson = toJson(payload);
        Long id = jdbc.query(
                "INSERT INTO notifications(user_id, type, payload) VALUES (?,?, ?::jsonb) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId, type, payloadJson
        );
        return id == null ? 0L : id;
    }

    public long insertIdempotent(long userId, String type, Map<String, Object> payload, String eventKey) {
        if (eventKey == null || eventKey.isBlank()) {
            return insert(userId, type, payload);
        }
        Map<String, Object> enriched = payload == null ? new HashMap<>() : new HashMap<>(payload);
        enriched.put("event_key", eventKey.trim());
        String payloadJson = toJson(enriched);
        Long id = jdbc.query(
                "INSERT INTO notifications(user_id, type, payload) VALUES (?,?, ?::jsonb) " +
                        "ON CONFLICT (user_id, type, (payload->>'event_key')) " +
                        "WHERE (payload->>'event_key') IS NOT NULL " +
                        "DO NOTHING " +
                        "RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId, type, payloadJson
        );
        return id == null ? 0L : id;
    }

    public boolean updatePayload(long notificationId, Map<String, Object> payload) {
        int updated = jdbc.update(
                "UPDATE notifications SET payload = ?::jsonb WHERE id = ?",
                toJson(payload), notificationId
        );
        return updated > 0;
    }

    public int[][] insertBatch(java.util.List<NotificationInsert> inserts) {
        if (inserts == null || inserts.isEmpty()) return new int[0][0];
        return jdbc.batchUpdate(
                "INSERT INTO notifications(user_id, type, payload) VALUES (?,?, ?::jsonb)",
                inserts,
                100,
                (ps, item) -> {
                    ps.setLong(1, item.userId());
                    ps.setString(2, item.type());
                    ps.setString(3, toJson(item.payload()));
                }
        );
    }

    public boolean existsByUserAndTypeSince(long userId, String type, OffsetDateTime since) {
        if (userId <= 0 || type == null || type.isBlank() || since == null) return false;
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(" +
                        "SELECT 1 FROM notifications WHERE user_id = ? AND type = ? AND created_at >= ?" +
                        ")",
                Boolean.class,
                userId,
                type,
                since
        );
        return Boolean.TRUE.equals(exists);
    }

    public int countByUserAndTypesSince(long userId, java.util.List<String> types, OffsetDateTime since) {
        if (userId <= 0 || types == null || types.isEmpty() || since == null) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(types.size(), "?"));
        String sql = "SELECT COUNT(*) FROM notifications " +
                "WHERE user_id = ? AND created_at >= ? AND type IN (" + placeholders + ")";
        Object[] params = new Object[types.size() + 2];
        params[0] = userId;
        params[1] = since;
        for (int i = 0; i < types.size(); i++) {
            params[i + 2] = types.get(i);
        }
        Integer count = jdbc.queryForObject(sql, Integer.class, params);
        return count == null ? 0 : count;
    }

    private String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return "{}";
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    public record NotificationInsert(long userId, String type, Map<String, Object> payload) {}

    public static class NotificationRow {
        public long id;
        public long userId;
        public String type;
        public Map<String, Object> payload = Map.of();
        public OffsetDateTime createdAt;
        public OffsetDateTime readAt;
        public OffsetDateTime dismissedAt;
    }
}
