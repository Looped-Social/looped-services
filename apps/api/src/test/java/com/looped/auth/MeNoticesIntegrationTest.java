package com.looped.auth;

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
import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class MeNoticesIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private String token(String sub) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("http://test-issuer")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject(sub)
                .audience(List.of("test-app"))
                .claim("email", sub + "@example.com");
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private long createProvisionedUser(String firebaseUid, String handle, String domain) {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES (?, ?) RETURNING id",
                Long.class,
                "Company-" + handle,
                domain
        );
        return jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_completed_at) VALUES (?,?,?,now()) RETURNING id",
                Long.class,
                firebaseUid,
                handle,
                companyId
        );
    }

    @Test
    void me_returns_pending_notice_for_eligible_user_and_hides_after_ack() throws Exception {
        long userId = createProvisionedUser("uid-notice-eligible", "notice_eligible", "notice-eligible.com");
        jdbc.update(
                "INSERT INTO user_notice_state(user_id, notice_key, eligible, first_eligible_at) VALUES (?,?,true,now())",
                userId,
                "workplace_fields_migration_v1"
        );

        String t = token("uid-notice-eligible");
        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notices", hasSize(1)))
                .andExpect(jsonPath("$.notices[0].key").value("workplace_fields_migration_v1"))
                .andExpect(jsonPath("$.notices[0].dismissible").value(true))
                .andExpect(jsonPath("$.notices[0].cta_label").value("Got it"));

        mockMvc.perform(post("/v1/me/notices/workplace_fields_migration_v1/ack")
                        .header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"dismiss\"}"))
                .andExpect(status().isNoContent());

        // Idempotent retry should remain 204 and preserve the first ack action.
        mockMvc.perform(post("/v1/me/notices/workplace_fields_migration_v1/ack")
                        .header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"cta\"}"))
                .andExpect(status().isNoContent());

        String ackAction = jdbc.queryForObject(
                "SELECT ack_action FROM user_notice_state WHERE user_id = ? AND notice_key = ?",
                String.class,
                userId,
                "workplace_fields_migration_v1"
        );
        OffsetDateTime acknowledgedAt = jdbc.queryForObject(
                "SELECT acknowledged_at FROM user_notice_state WHERE user_id = ? AND notice_key = ?",
                OffsetDateTime.class,
                userId,
                "workplace_fields_migration_v1"
        );
        assertEquals("dismiss", ackAction);
        assertNotNull(acknowledgedAt);

        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notices", hasSize(0)));
    }

    @Test
    void me_returns_empty_notices_for_non_eligible_user() throws Exception {
        createProvisionedUser("uid-notice-noneligible", "notice_noneligible", "notice-noneligible.com");
        String t = token("uid-notice-noneligible");

        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notices", hasSize(0)));
    }

    @Test
    void acknowledge_unknown_notice_returns_not_found() throws Exception {
        createProvisionedUser("uid-notice-unknown", "notice_unknown", "notice-unknown.com");
        String t = token("uid-notice-unknown");

        mockMvc.perform(post("/v1/me/notices/not_real/ack")
                        .header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"dismiss\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("notice_not_found"));
    }

    @Test
    void acknowledge_invalid_action_returns_unprocessable_entity() throws Exception {
        createProvisionedUser("uid-notice-bad-action", "notice_bad_action", "notice-bad-action.com");
        String t = token("uid-notice-bad-action");

        mockMvc.perform(post("/v1/me/notices/workplace_fields_migration_v1/ack")
                        .header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"later\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_notice_action"));
    }
}
