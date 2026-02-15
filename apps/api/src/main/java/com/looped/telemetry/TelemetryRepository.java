package com.looped.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class TelemetryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public TelemetryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int insertBatch(long userId, long principalId, OffsetDateTime sentAt, List<TelemetryEventInsert> events) {
        if (events == null || events.isEmpty()) return 0;
        int[][] res = jdbc.batchUpdate(
                "INSERT INTO telemetry_events(" +
                        "user_id, principal_id, session_id, event_id, type, occurred_at, sent_at, " +
                        "post_id, comment_id, community_id, " +
                        "feed_mode, feed_community_id, feed_request_id, feed_position, payload" +
                        ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?::jsonb) " +
                        "ON CONFLICT (event_id) DO NOTHING",
                events,
                200,
                (ps, e) -> {
                    ps.setLong(1, userId);
                    ps.setLong(2, principalId);
                    ps.setObject(3, e.sessionId());
                    ps.setObject(4, e.eventId());
                    ps.setString(5, e.type());
                    ps.setObject(6, e.occurredAt());
                    ps.setObject(7, sentAt);
                    if (e.postId() == null) ps.setObject(8, null);
                    else ps.setLong(8, e.postId());
                    if (e.commentId() == null) ps.setObject(9, null);
                    else ps.setLong(9, e.commentId());
                    if (e.communityId() == null) ps.setObject(10, null);
                    else ps.setLong(10, e.communityId());
                    ps.setString(11, e.feedMode());
                    if (e.feedCommunityId() == null) ps.setObject(12, null);
                    else ps.setLong(12, e.feedCommunityId());
                    ps.setObject(13, e.feedRequestId());
                    if (e.feedPosition() == null) ps.setObject(14, null);
                    else ps.setInt(14, e.feedPosition());
                    ps.setString(15, toJson(e.payload()));
                }
        );
        int inserted = 0;
        for (int[] batch : res) {
            if (batch == null) continue;
            for (int c : batch) {
                if (c > 0) inserted += c;
            }
        }
        return inserted;
    }

    private String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return "{}";
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    public record TelemetryEventInsert(
            UUID sessionId,
            UUID eventId,
            String type,
            OffsetDateTime occurredAt,
            Long postId,
            Long commentId,
            Long communityId,
            String feedMode,
            Long feedCommunityId,
            UUID feedRequestId,
            Integer feedPosition,
            Map<String, Object> payload
    ) {}
}

