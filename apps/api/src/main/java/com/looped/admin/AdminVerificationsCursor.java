package com.looped.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * Opaque keyset cursor for GET /v1/admin/verifications.
 *
 * <p>Includes a small amount of filter/sort context so cursors from one query
 * can't be applied to a different filter set incorrectly.</p>
 */
public final class AdminVerificationsCursor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int VERSION = 1;

    private AdminVerificationsCursor() {}

    record Payload(int v, String ts, long id, String status, String method, String sort, String q) {}

    public record Decoded(OffsetDateTime timestamp, long id, String status, String method, String sort, String qHash) {}

    public static String encode(OffsetDateTime timestamp, long id, String status, String method, String sort, String qHash) {
        try {
            String raw = JSON.writeValueAsString(new Payload(VERSION, timestamp.toString(), id, status, method, sort, qHash));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("bad cursor");
        }
    }

    public static Decoded decode(String cursor) throws IllegalArgumentException {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Payload payload = JSON.readValue(raw, Payload.class);
            if (payload.v != VERSION) throw new IllegalArgumentException("bad cursor");
            if (payload.ts == null || payload.ts.isBlank()) throw new IllegalArgumentException("bad cursor");
            var ts = OffsetDateTime.parse(payload.ts);
            return new Decoded(ts, payload.id, payload.status, payload.method, payload.sort, payload.q);
        } catch (RuntimeException | JsonProcessingException e) {
            throw new IllegalArgumentException("bad cursor");
        }
    }
}
