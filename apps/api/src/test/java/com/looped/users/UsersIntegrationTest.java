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
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("http://test-issuer")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject(sub)
                .audience(List.of("test-app"))
                .claim("email", email)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
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
                .andExpect(jsonPath("$.items[0].content", equalTo("post-3")))
                .andExpect(jsonPath("$.items[1].content", equalTo("post-2")))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String cursor = firstPage.getResponse().getContentAsString().replaceAll(".*\"next_cursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/v1/users/" + targetId + "/posts?cursor=" + cursor)
                        .header("Authorization", "Bearer " + token("uid-actor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].content", equalTo("post-1")))
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
    void hard_delete_reserves_handle_and_email() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('ResCo','res.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, email, company_id) VALUES (?,?,?,?)",
                "uid-reserve", "reserved", "reserved@res.co", companyId);

        String auth = "Bearer " + token("uid-reserve");
        mockMvc.perform(post("/v1/users/me/delete")
                        .header("Authorization", auth))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/v1/users/username/availability?username=reserved")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available", equalTo(false)));

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
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", equalTo("email_taken")));

        Integer tombstones = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_tombstones WHERE handle = 'reserved' AND email = 'reserved@res.co'",
                Integer.class
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, tombstones.intValue());
    }
}
