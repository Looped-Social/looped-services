package com.looped.users;

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
class UserContentIntegrationTest extends PostgresTestBase {

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
    void content_combines_posts_and_replies_with_stable_pagination() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long actorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-content-actor", "actor", companyId);
        long actorPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, actorId);

        long targetId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-content-target", "target", companyId);
        long targetPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, targetId);

        Instant base = Instant.now();

        long targetPost1 = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, targetId, targetPrincipalId, companyId, "p1", Timestamp.from(base.minusSeconds(100)));
        long targetPost2 = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, targetId, targetPrincipalId, companyId, "p2", Timestamp.from(base.minusSeconds(60)));

        long hostPost = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, actorId, actorPrincipalId, companyId, "host", Timestamp.from(base.minusSeconds(200)));

        jdbc.update("INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?,?)",
                hostPost, targetId, targetPrincipalId, companyId, "r1", Timestamp.from(base.minusSeconds(80)));
        jdbc.update("INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?,?)",
                hostPost, targetId, targetPrincipalId, companyId, "r2", Timestamp.from(base.minusSeconds(40)));

        String auth = "Bearer " + token("uid-content-actor");

        var r1 = mockMvc.perform(get("/v1/users/" + targetId + "/content?limit=3").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].type", equalTo("reply")))
                .andExpect(jsonPath("$.items[0].reply.content", equalTo("r2")))
                .andExpect(jsonPath("$.items[1].type", equalTo("post")))
                .andExpect(jsonPath("$.items[1].post.id", equalTo((int) targetPost2)))
                .andExpect(jsonPath("$.items[1].post.content", equalTo("p2")))
                .andExpect(jsonPath("$.items[2].type", equalTo("reply")))
                .andExpect(jsonPath("$.items[2].reply.content", equalTo("r1")))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String cursor = r1.getResponse().getContentAsString().replaceAll(".*\"next_cursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/v1/users/" + targetId + "/content?cursor=" + cursor + "&limit=3").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].type", equalTo("post")))
                .andExpect(jsonPath("$.items[0].post.content", equalTo("p1")))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }
}
