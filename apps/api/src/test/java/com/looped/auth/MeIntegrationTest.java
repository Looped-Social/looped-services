package com.looped.auth;

import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class MeIntegrationTest extends PostgresTestBase {

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
                .claim("email", sub + "@example.com")
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Test
    void me_authenticated_unprovisioned() throws Exception {
        String t = token("uid-unprovisioned");
        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisioned").value(false))
                .andExpect(jsonPath("$.sub").value("uid-unprovisioned"));
    }

    @Test
    void me_authenticated_provisioned() throws Exception {
        // Arrange: create company and user matching firebase_uid
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme', 'acme.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-provisioned", "alice", companyId);

        String t = token("uid-provisioned");
        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisioned").value(true))
                .andExpect(jsonPath("$.user.handle").value("alice"));
    }

    @Test
    void me_includes_verification_status_when_present() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('AcmeV', 'acmev.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-verify-me", "anna", companyId);
        // mark verified via direct insert to verifications
        jdbc.update("INSERT INTO verifications(user_id, method, verified, verified_at) SELECT id, 'email', true, now() FROM users WHERE firebase_uid=?",
                "uid-verify-me");

        String t = token("uid-verify-me");
        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisioned").value(true))
                .andExpect(jsonPath("$.user.verification.verified").value(true))
                .andExpect(jsonPath("$.user.verification.method").value("email"));
    }

    @Test
    void me_reactivates_recently_deactivated() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Reacme', 'reacme.com') RETURNING id",
                Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-reactivate", "reactor", companyId);
        OffsetDateTime deletedAt = OffsetDateTime.now().minusDays(10);
        jdbc.update("UPDATE users SET deleted_at = ?, deleted_by = ? WHERE id = ?", deletedAt, userId, userId);

        String t = token("uid-reactivate");
        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisioned").value(true))
                .andExpect(jsonPath("$.user.handle").value("reactor"));

        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ? AND deleted_at IS NULL",
                Integer.class, userId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, active.intValue());
    }

    @Test
    void me_marks_account_deleted_after_retention_window() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Oldco', 'oldco.com') RETURNING id",
                Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, email, company_id) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-purged", "purged", "purged@oldco.com", companyId);
        OffsetDateTime deletedAt = OffsetDateTime.now().minusDays(91);
        jdbc.update("UPDATE users SET deleted_at = ?, deleted_by = ? WHERE id = ?", deletedAt, userId, userId);

        String t = token("uid-purged");
        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisioned").value(false))
                .andExpect(jsonPath("$.account_deleted").value(true));

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE firebase_uid = 'uid-purged'",
                Integer.class
        );
        Integer tombstones = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_tombstones WHERE firebase_uid = 'uid-purged'",
                Integer.class
        );
        org.junit.jupiter.api.Assertions.assertEquals(0, remaining.intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, tombstones.intValue());
    }
}
