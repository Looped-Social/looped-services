package com.looped.posts;

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
class PostCollectionsIntegrationTest extends PostgresTestBase {

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
    void liked_posts_list() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-like','alex',?) RETURNING id", Long.class, companyId);
        long author = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-author','taylor',?) RETURNING id", Long.class, companyId);

        Instant base = Instant.now();
        long post1 = jdbc.queryForObject("INSERT INTO posts(author_id, company_id, content, created_at) VALUES (?,?,?,?) RETURNING id",
                Long.class, author, companyId, "alpha", Timestamp.from(base.minusSeconds(60)));
        long post2 = jdbc.queryForObject("INSERT INTO posts(author_id, company_id, content, created_at) VALUES (?,?,?,?) RETURNING id",
                Long.class, author, companyId, "beta", Timestamp.from(base.minusSeconds(30)));

        jdbc.update("INSERT INTO likes(user_id, post_id, created_at) VALUES (?,?,?)", userId, post1, Timestamp.from(base.minusSeconds(10)));
        jdbc.update("INSERT INTO likes(user_id, post_id, created_at) VALUES (?,?,?)", userId, post2, Timestamp.from(base.minusSeconds(5)));

        var first = mockMvc.perform(get("/v1/posts/liked?limit=1")
                        .header("Authorization", "Bearer " + token("uid-like")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].content", equalTo("alpha")))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String cursor = first.getResponse().getContentAsString().replaceAll(".*\"next_cursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/v1/posts/liked?cursor=" + cursor)
                        .header("Authorization", "Bearer " + token("uid-like")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].content", equalTo("beta")))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void save_and_unsave_flow() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-save','alex',?) RETURNING id", Long.class, companyId);
        long author = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-author','taylor',?) RETURNING id", Long.class, companyId);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, company_id, content) VALUES (?,?,?) RETURNING id",
                Long.class, author, companyId, "alpha");

        String auth = "Bearer " + token("uid-save");

        mockMvc.perform(post("/v1/posts/" + postId + "/save")
                        .header("Authorization", auth)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.post_id", equalTo((int) postId)))
                .andExpect(jsonPath("$.saved", equalTo(true)));

        mockMvc.perform(get("/v1/posts/saved")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].content", equalTo("alpha")));

        mockMvc.perform(delete("/v1/posts/" + postId + "/save")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved", equalTo(false)));

        mockMvc.perform(get("/v1/posts/saved")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }
}

