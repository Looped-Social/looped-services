package com.looped.telemetry;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class TelemetryIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcTemplate jdbc;

    private String token(String sub) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("http://test-issuer")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject(sub)
                .audience(List.of("test-app"))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Test
    void telemetry_accepts_and_dedupes_events() throws Exception {
        long companyId = jdbc.queryForObject("SELECT id FROM companies WHERE domain = 'looped.global' LIMIT 1", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id, onboarding_step, onboarding_completed_at) VALUES (?,?,?,?, now())",
                "uid-telemetry", "telemetry", companyId, "verification_notifications");

        String auth = "Bearer " + token("uid-telemetry");
        UUID eventId = UUID.randomUUID();
        long nowMs = Instant.now().toEpochMilli();

        String body = """
                {
                  "session_id": "7f91d6d4-ef9c-4df5-a8e5-1f2e70b0c10a",
                  "sent_at_ms": %d,
                  "events": [
                    {
                      "event_id": "%s",
                      "type": "feed_impression",
                      "occurred_at_ms": %d,
                      "post_id": 123,
                      "feed": { "mode": "for_you", "request_id": "b9a356f7-7e2c-49c2-b9bb-7e3c1a0c3c73", "position": 4 },
                      "data": { "visible_ms": 900, "can_interact": true }
                    }
                  ]
                }
                """.formatted(nowMs, eventId, nowMs);

        mockMvc.perform(post("/v1/telemetry/events")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", equalTo("ok")))
                .andExpect(jsonPath("$.accepted", equalTo(1)))
                .andExpect(jsonPath("$.dropped", equalTo(0)));

        Integer c1 = jdbc.queryForObject("SELECT COUNT(*) FROM telemetry_events", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, c1);

        mockMvc.perform(post("/v1/telemetry/events")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted", equalTo(0)))
                .andExpect(jsonPath("$.dropped", equalTo(1)));

        Integer c2 = jdbc.queryForObject("SELECT COUNT(*) FROM telemetry_events", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, c2);
    }

    @Test
    void telemetry_drops_unknown_event_types() throws Exception {
        long companyId = jdbc.queryForObject("SELECT id FROM companies WHERE domain = 'looped.global' LIMIT 1", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id, onboarding_step, onboarding_completed_at) VALUES (?,?,?,?, now())",
                "uid-telemetry-2", "telemetry2", companyId, "verification_notifications");

        String auth = "Bearer " + token("uid-telemetry-2");
        long nowMs = Instant.now().toEpochMilli();

        String body = """
                {
                  "session_id": "7f91d6d4-ef9c-4df5-a8e5-1f2e70b0c10a",
                  "events": [
                    { "event_id": "%s", "type": "unknown_event", "occurred_at_ms": %d }
                  ]
                }
                """.formatted(UUID.randomUUID(), nowMs);

        mockMvc.perform(post("/v1/telemetry/events")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted", equalTo(0)))
                .andExpect(jsonPath("$.dropped", equalTo(1)));
    }

    @Test
    void telemetry_rejects_missing_session_id() throws Exception {
        long companyId = jdbc.queryForObject("SELECT id FROM companies WHERE domain = 'looped.global' LIMIT 1", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id, onboarding_step, onboarding_completed_at) VALUES (?,?,?,?, now())",
                "uid-telemetry-3", "telemetry3", companyId, "verification_notifications");

        String auth = "Bearer " + token("uid-telemetry-3");
        String body = "{\"events\":[]}";

        mockMvc.perform(post("/v1/telemetry/events")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", equalTo("invalid_body")));
    }
}

