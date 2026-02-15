package com.looped.users;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class UsersIntegrationTest extends PostgresTestBase {

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

    private String tokenWithEmail(String sub, String email) {
        return tokenWithEmail(sub, email, null);
    }

    private String tokenWithEmail(String sub, String email, Boolean emailVerified) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("http://test-issuer")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject(sub)
                .audience(List.of("test-app"))
                .claim("email", email);
        if (emailVerified != null) {
            claims.claim("email_verified", emailVerified);
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    @Test
    void profile_returns_same_company_user() throws Exception {
        long acmeId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long actorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-actor','alex',?) RETURNING id", Long.class, acmeId);
        long targetId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-target','taylor',?) RETURNING id", Long.class, acmeId);
        jdbc.update("INSERT INTO verifications(user_id, method, verified, verified_at) VALUES (?,?,true, now())",
                targetId, "email");

        mockMvc.perform(get("/v1/users/" + targetId)
                        .header("Authorization", "Bearer " + token("uid-actor"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) targetId)))
                .andExpect(jsonPath("$.handle", equalTo("taylor")))
                .andExpect(jsonPath("$.company_id", equalTo((int) acmeId)))
                .andExpect(jsonPath("$.verification.method", equalTo("email")))
                .andExpect(jsonPath("$.verification.verified", equalTo(true)));
    }

    @Test
    void user_posts_paginate_in_order() throws Exception {
        long acmeId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long actorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-actor','alex',?) RETURNING id", Long.class, acmeId);
        long targetId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-target','taylor',?) RETURNING id", Long.class, acmeId);
        long targetPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, targetId);

        Instant base = Instant.now();
        for (int i = 1; i <= 3; i++) {
            jdbc.update(
                    "INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?)",
                    targetId, targetPrincipal, acmeId, "post-" + i, Timestamp.from(base.minusSeconds(30L * i))
            );
        }

        var firstPage = mockMvc.perform(get("/v1/users/" + targetId + "/posts?limit=2")
                        .header("Authorization", "Bearer " + token("uid-actor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].content", equalTo("post-1")))
                .andExpect(jsonPath("$.items[1].content", equalTo("post-2")))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String cursor = firstPage.getResponse().getContentAsString().replaceAll(".*\"next_cursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/v1/users/" + targetId + "/posts?cursor=" + cursor)
                        .header("Authorization", "Bearer " + token("uid-actor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].content", equalTo("post-3")))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void cross_company_profile_forbidden() throws Exception {
        long acmeId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long otherId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Other','other.com') RETURNING id", Long.class);
        long actorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-actor','alex',?) RETURNING id", Long.class, acmeId);
        long targetId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-target','taylor',?) RETURNING id", Long.class, otherId);

        mockMvc.perform(get("/v1/users/" + targetId)
                        .header("Authorization", "Bearer " + token("uid-actor")))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_me_hard_is_idempotent() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('DelCo','del.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-delete", "dora", companyId);

        String auth = "Bearer " + token("uid-delete");

        mockMvc.perform(delete("/v1/users/me")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("deleted")))
                .andExpect(jsonPath("$.firebase_deleted", equalTo(false)))
                .andExpect(jsonPath("$.firebase_status", equalTo("skipped")));

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE firebase_uid='uid-delete'", Integer.class);
        org.junit.jupiter.api.Assertions.assertNotNull(count);
        org.junit.jupiter.api.Assertions.assertEquals(0, count.intValue());

        mockMvc.perform(delete("/v1/users/me")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("deleted")))
                .andExpect(jsonPath("$.firebase_deleted", equalTo(false)))
                .andExpect(jsonPath("$.firebase_status", equalTo("skipped")));
    }

    @Test
    void delete_me_soft_marks_deleted() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('SoftCo','soft.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-delete-soft", "sara", companyId);

        String auth = "Bearer " + token("uid-delete-soft");

        mockMvc.perform(delete("/v1/users/me?mode=soft")
                        .header("Authorization", auth))
                .andExpect(status().isNoContent());

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE firebase_uid='uid-delete-soft' AND deleted_at IS NOT NULL", Integer.class);
        org.junit.jupiter.api.Assertions.assertNotNull(count);
        org.junit.jupiter.api.Assertions.assertEquals(1, count.intValue());

        mockMvc.perform(delete("/v1/users/me?mode=soft")
                        .header("Authorization", auth))
                .andExpect(status().isNoContent());
    }

    @Test
    void deactivate_endpoint_soft_deletes() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('DeactCo','deact.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-deactivate", "mara", companyId);

        String auth = "Bearer " + token("uid-deactivate");

        mockMvc.perform(post("/v1/users/me/deactivate")
                        .header("Authorization", auth))
                .andExpect(status().isNoContent());

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE firebase_uid='uid-deactivate' AND deleted_at IS NOT NULL", Integer.class);
        org.junit.jupiter.api.Assertions.assertNotNull(count);
        org.junit.jupiter.api.Assertions.assertEquals(1, count.intValue());
    }

    @Test
    void delete_endpoint_hard_deletes() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('DelUi','delui.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-delete-ui", "tina", companyId);

        String auth = "Bearer " + token("uid-delete-ui");

        mockMvc.perform(post("/v1/users/me/delete")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("deleted")))
                .andExpect(jsonPath("$.firebase_deleted", equalTo(false)))
                .andExpect(jsonPath("$.firebase_status", equalTo("skipped")));

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE firebase_uid='uid-delete-ui'", Integer.class);
        org.junit.jupiter.api.Assertions.assertNotNull(count);
        org.junit.jupiter.api.Assertions.assertEquals(0, count.intValue());
    }

    @Test
    void hard_delete_reserves_handle_allows_email_reuse() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('ResCo','res.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, email, company_id) VALUES (?,?,?,?)",
                "uid-reserve", "reserved", "reserved@res.co", companyId);

        String auth = "Bearer " + token("uid-reserve");
        mockMvc.perform(post("/v1/users/me/delete")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("deleted")))
                .andExpect(jsonPath("$.firebase_deleted", equalTo(false)))
                .andExpect(jsonPath("$.firebase_status", equalTo("skipped")));

        mockMvc.perform(get("/v1/users/username/availability?username=reserved")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available", equalTo(false)));

        jdbc.update("UPDATE user_tombstones SET purged_at = now() - interval '15 days' WHERE handle = 'reserved'");

        mockMvc.perform(get("/v1/users/username/availability?username=reserved")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available", equalTo(true)));

        String onboardBody = """
                {
                  "username": "newuser1",
                  "firstName": "New",
                  "lastName": "User",
                  "dateOfBirth": "1990-01-01"
                }
                """;
        mockMvc.perform(post("/v1/users/onboard")
                        .header("Authorization", "Bearer " + tokenWithEmail("uid-new", "reserved@res.co"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(onboardBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.handle", equalTo("newuser1")));

        Integer tombstones = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_tombstones WHERE handle = 'reserved' AND email IS NULL",
                Integer.class
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, tombstones.intValue());
    }

    @Test
    void onboard_claims_existing_user_by_verified_email_when_uid_changes() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('ClaimOnb','claim-onb.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, email, company_id) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-existing", "existing", "existing@claim-onb.co", companyId
        );

        String onboardBody = """
                {
                  "username": "freshname",
                  "firstName": "Fresh",
                  "lastName": "User",
                  "dateOfBirth": "1991-01-01"
                }
                """;
        mockMvc.perform(post("/v1/users/onboard")
                        .header("Authorization", "Bearer " + tokenWithEmail("uid-new-provider", "existing@claim-onb.co", true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(onboardBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", equalTo((int) userId)))
                .andExpect(jsonPath("$.handle", equalTo("existing")));

        Integer totalUsers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE LOWER(email) = LOWER('existing@claim-onb.co')",
                Integer.class
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, totalUsers.intValue());
        String mappedUid = jdbc.queryForObject(
                "SELECT firebase_uid FROM users WHERE id = ?",
                String.class, userId
        );
        org.junit.jupiter.api.Assertions.assertEquals("uid-new-provider", mappedUid);
    }

    @Test
    void username_availability_reports_owned_by_me() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('OwnCo','own.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-own", "mara", companyId);

        String auth = "Bearer " + token("uid-own");
        mockMvc.perform(get("/v1/users/username/availability?username=mara")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available", equalTo(true)))
                .andExpect(jsonPath("$.owned_by_me", equalTo(true)));
    }

    @Test
    void display_specialization_endpoint_is_reachable_during_onboarding() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('OnbDispCo','onbdisp.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id, onboarding_step, onboarding_completed_at) VALUES (?,?,?,?,NULL)",
                "uid-onb-disp", "onbdisp", companyId, "verification");

        String auth = "Bearer " + token("uid-onb-disp");
        mockMvc.perform(put("/v1/users/me/display-specialization")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"specializationId\":999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", equalTo("specialization_not_found")));
    }

    @Test
    void update_onboarding_accepts_intermediate_steps() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('OnbMidCo','onbmid.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-onb-mid", "onbmid", companyId
        );

        String auth = "Bearer " + token("uid-onb-mid");
        mockMvc.perform(put("/v1/users/me/onboarding")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"step\":\"select_company\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_complete", equalTo(false)))
                .andExpect(jsonPath("$.onboarding_step", equalTo("select_company")));

        String storedStep = jdbc.queryForObject(
                "SELECT onboarding_step FROM users WHERE id = ?",
                String.class, userId
        );
        org.junit.jupiter.api.Assertions.assertEquals("select_company", storedStep);
    }

    @Test
    void update_onboarding_invalid_step_returns_actionable_payload() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('OnbErrCo','onberr.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id, onboarding_step) VALUES (?,?,?,?)",
                "uid-onb-invalid", "onberr", companyId, "verification");

        String auth = "Bearer " + token("uid-onb-invalid");
        mockMvc.perform(put("/v1/users/me/onboarding")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"step\":\"identity\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", equalTo("invalid_onboarding_step")))
                .andExpect(jsonPath("$.current_step", equalTo("verification")))
                .andExpect(jsonPath("$.allowed_next_steps", hasSize(4)))
                .andExpect(jsonPath("$.allowed_next_steps[0]", equalTo("profile_setup")))
                .andExpect(jsonPath("$.allowed_next_steps[1]", equalTo("select_company")))
                .andExpect(jsonPath("$.allowed_next_steps[2]", equalTo("verification")))
                .andExpect(jsonPath("$.allowed_next_steps[3]", equalTo("verification_notifications")));
    }

    @Test
    void update_onboarding_marks_complete() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('OnbCo','onb.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-onb", "onboarded", companyId
        );

        String auth = "Bearer " + token("uid-onb");
        mockMvc.perform(put("/v1/users/me/onboarding")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"step\":\"verification_notifications\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_complete", equalTo(true)))
                .andExpect(jsonPath("$.onboarding_step", equalTo("verification_notifications")));

        Timestamp completedAt = jdbc.queryForObject(
                "SELECT onboarding_completed_at FROM users WHERE id = ?",
                Timestamp.class, userId
        );
        org.junit.jupiter.api.Assertions.assertNotNull(completedAt);
    }
}
