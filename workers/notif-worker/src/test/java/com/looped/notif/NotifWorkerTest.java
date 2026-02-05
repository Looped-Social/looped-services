package com.looped.notif;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NotifWorkerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Test
    void buildNotification_returnsNull_whenMissingRequiredFields() {
        Map<String, Object> event = new HashMap<>();
        event.put("apns_token", "token");
        event.put("title", "Hello");
        // missing "body"

        assertNull(NotifWorker.buildNotification("com.looped.app", event, Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test
    void buildNotification_includesDefaults_andCustomProperties() throws Exception {
        Map<String, Object> event = new HashMap<>();
        event.put("apns_token", "token");
        event.put("title", "T");
        event.put("body", "B");
        event.put("deeplink", "looped://posts/123");
        event.put("user_id", 123);

        Instant sentAt = Instant.parse("2026-01-01T00:00:00Z");
        SimpleApnsPushNotification notif = NotifWorker.buildNotification("com.looped.app", event, sentAt);
        assertNotNull(notif);
        assertEquals("token", notif.getToken());
        assertEquals("com.looped.app", notif.getTopic());

        Map<String, Object> payload = MAPPER.readValue(notif.getPayload(), MAP_TYPE);
        assertEquals("push", payload.get("type"));
        assertEquals(sentAt.toString(), payload.get("sent_at"));
        assertEquals("looped://posts/123", payload.get("deeplink"));
        assertEquals(123, ((Number) payload.get("user_id")).intValue());

        assertFalse(payload.containsKey("apns_token"));
        assertFalse(payload.containsKey("title"));
        assertFalse(payload.containsKey("body"));
        assertFalse(payload.containsKey("badge"));

        @SuppressWarnings("unchecked")
        Map<String, Object> aps = (Map<String, Object>) payload.get("aps");
        assertNotNull(aps);

        @SuppressWarnings("unchecked")
        Map<String, Object> alert = (Map<String, Object>) aps.get("alert");
        assertNotNull(alert);
        assertEquals("T", alert.get("title"));
        assertEquals("B", alert.get("body"));
        assertEquals("default", aps.get("sound"));
    }

    @Test
    void buildNotification_setsBadge_whenNonNegative() throws Exception {
        Map<String, Object> event = new HashMap<>();
        event.put("apns_token", "token");
        event.put("title", "T");
        event.put("body", "B");
        event.put("badge", 0);

        SimpleApnsPushNotification notif = NotifWorker.buildNotification("com.looped.app", event, Instant.parse("2026-01-01T00:00:00Z"));
        assertNotNull(notif);

        Map<String, Object> payload = MAPPER.readValue(notif.getPayload(), MAP_TYPE);
        @SuppressWarnings("unchecked")
        Map<String, Object> aps = (Map<String, Object>) payload.get("aps");
        assertNotNull(aps);
        assertEquals(0, ((Number) aps.get("badge")).intValue());
    }

    @Test
    void buildNotification_omitsBadge_whenNegative() throws Exception {
        Map<String, Object> event = new HashMap<>();
        event.put("apns_token", "token");
        event.put("title", "T");
        event.put("body", "B");
        event.put("badge", -1);

        SimpleApnsPushNotification notif = NotifWorker.buildNotification("com.looped.app", event, Instant.parse("2026-01-01T00:00:00Z"));
        assertNotNull(notif);

        Map<String, Object> payload = MAPPER.readValue(notif.getPayload(), MAP_TYPE);
        @SuppressWarnings("unchecked")
        Map<String, Object> aps = (Map<String, Object>) payload.get("aps");
        assertNotNull(aps);
        assertFalse(aps.containsKey("badge"));
    }

    @Test
    void buildNotification_clampsBadge_whenTooLarge() throws Exception {
        Map<String, Object> event = new HashMap<>();
        event.put("apns_token", "token");
        event.put("title", "T");
        event.put("body", "B");
        event.put("badge", (long) Integer.MAX_VALUE + 123L);

        SimpleApnsPushNotification notif = NotifWorker.buildNotification("com.looped.app", event, Instant.parse("2026-01-01T00:00:00Z"));
        assertNotNull(notif);

        Map<String, Object> payload = MAPPER.readValue(notif.getPayload(), MAP_TYPE);
        @SuppressWarnings("unchecked")
        Map<String, Object> aps = (Map<String, Object>) payload.get("aps");
        assertNotNull(aps);
        assertEquals(Integer.MAX_VALUE, ((Number) aps.get("badge")).intValue());
    }
}

