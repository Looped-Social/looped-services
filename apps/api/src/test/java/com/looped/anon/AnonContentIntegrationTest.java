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
class AnonContentIntegrationTest extends PostgresTestBase {

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
    void anon_profile_content_mixes_posts_and_replies_with_stable_pagination() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long actorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-content-actor", "actor", companyId);
        long actorPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, actorId);

        byte[] pubkey = new byte[32];
        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, pubkey, "anon-content"
        );
        long anonPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, anon_profile_id) VALUES ('anon', ?) RETURNING id",
                Long.class, anonProfileId
        );

        Instant base = Instant.now();

        long anonPost1 = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, is_anon, anon_profile_id, anon_company_id, created_at) " +
                        "VALUES (NULL,?,?,?,?,?,?,?) RETURNING id",
                Long.class, anonPrincipalId, companyId, "p1", true, anonProfileId, companyId, Timestamp.from(base.minusSeconds(100))
        );
        jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, is_anon, anon_profile_id, anon_company_id, created_at) " +
                        "VALUES (NULL,?,?,?,?,?,?,?) RETURNING id",
                Long.class, anonPrincipalId, companyId, "p2", true, anonProfileId, companyId, Timestamp.from(base.minusSeconds(60))
        );

        long hostPost = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, actorId, actorPrincipalId, companyId, "host", Timestamp.from(base.minusSeconds(200))
        );
        jdbc.update(
                "INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?,?)",
                hostPost, null, anonPrincipalId, companyId, "r1", Timestamp.from(base.minusSeconds(80))
        );
        jdbc.update(
                "INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?,?)",
                hostPost, null, anonPrincipalId, companyId, "r2", Timestamp.from(base.minusSeconds(40))
        );

        String auth = "Bearer " + token("uid-anon-content-actor");

        var r1 = mockMvc.perform(get("/v1/anon/" + anonProfileId + "/content?limit=3").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].type", equalTo("reply")))
                .andExpect(jsonPath("$.items[0].reply.content", equalTo("r2")))
                .andExpect(jsonPath("$.items[1].type", equalTo("post")))
                .andExpect(jsonPath("$.items[2].type", equalTo("reply")))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String cursor = r1.getResponse().getContentAsString().replaceAll(".*\"next_cursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/v1/anon/" + anonProfileId + "/content?cursor=" + cursor + "&limit=3").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].type", equalTo("post")))
                .andExpect(jsonPath("$.items[0].post.id", equalTo((int) anonPost1)))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }
}

