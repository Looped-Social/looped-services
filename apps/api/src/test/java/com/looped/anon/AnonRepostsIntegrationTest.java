package com.looped.anon;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class AnonRepostsIntegrationTest extends PostgresTestBase {

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
    void anon_profile_reposts_returns_posts_paginated_by_repost_timestamp() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long actorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-reposts-actor", "actor", companyId);
        jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, actorId);

        byte[] pubkey = new byte[32];
        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, pubkey, "anon-reposts"
        );
        long anonPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, anon_profile_id) VALUES ('anon', ?) RETURNING id",
                Long.class, anonProfileId
        );

        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-reposts-author", "author", companyId);
        long authorPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);

        Instant base = Instant.now();
        long post1 = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, "p1", Timestamp.from(base.minusSeconds(200)));
        long post2 = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, "p2", Timestamp.from(base.minusSeconds(100)));
        long post3 = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, "p3", Timestamp.from(base.minusSeconds(50)));

        long repostId1 = jdbc.queryForObject(
                "INSERT INTO post_reposts(reposter_principal_id, post_id, created_at) VALUES (?,?,?) RETURNING id",
                Long.class, anonPrincipalId, post1, Timestamp.from(base.minusSeconds(30))
        );
        jdbc.queryForObject(
                "INSERT INTO post_reposts(reposter_principal_id, post_id, created_at) VALUES (?,?,?) RETURNING id",
                Long.class, anonPrincipalId, post2, Timestamp.from(base.minusSeconds(20))
        );
        jdbc.queryForObject(
                "INSERT INTO post_reposts(reposter_principal_id, post_id, created_at) VALUES (?,?,?) RETURNING id",
                Long.class, anonPrincipalId, post3, Timestamp.from(base.minusSeconds(10))
        );

        String auth = "Bearer " + token("uid-anon-reposts-actor");

        var r1 = mockMvc.perform(get("/v1/anon/" + anonProfileId + "/reposts?limit=2").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id", equalTo((int) post3)))
                .andExpect(jsonPath("$.items[1].id", equalTo((int) post2)))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String cursor = r1.getResponse().getContentAsString().replaceAll(".*\"next_cursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/v1/anon/" + anonProfileId + "/reposts?cursor=" + cursor + "&limit=2").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo((int) post1)))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());

        org.junit.jupiter.api.Assertions.assertTrue(repostId1 > 0);
    }
}

