package com.looped.posts;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.JwsHeader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class FeedIntegrationTest extends PostgresTestBase {

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
    void feed_returns_company_posts_with_pagination() throws Exception {
        long acmeId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long otherId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Other','other.com') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-feed", "erin", acmeId);

        long author = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid=?", Long.class, "uid-feed");
        Instant base = Instant.now();
        // Insert 5 posts for Acme with descending times, and 2 posts for Other company
        for (int i = 1; i <= 5; i++) {
            Long id = jdbc.queryForObject(
                    "INSERT INTO posts(author_id, company_id, content, created_at) VALUES (?,?,?,?) RETURNING id",
                    Long.class,
                    author, acmeId, "p" + i, Timestamp.from(base.minusSeconds(60L * i))
            );
        }
        for (int i = 1; i <= 2; i++) {
            jdbc.update(
                    "INSERT INTO posts(author_id, company_id, content, created_at) VALUES (?,?,?,?)",
                    author, otherId, "x" + i, Timestamp.from(base.minusSeconds(60L * (10 + i)))
            );
        }

        String auth = "Bearer " + token("uid-feed");

        var r1 = mockMvc.perform(get("/v1/feed?limit=2")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].content", equalTo("p5")))
                .andExpect(jsonPath("$.items[1].content", equalTo("p4")))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String next = r1.getResponse().getContentAsString().replaceAll(".*\"next_cursor\":\"([^\"]+)\".*", "$1");

        var r2 = mockMvc.perform(get("/v1/feed?limit=2&cursor=" + next)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].content", equalTo("p3")))
                .andExpect(jsonPath("$.items[1].content", equalTo("p2")))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String next2 = r2.getResponse().getContentAsString().replaceAll(".*\"next_cursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/v1/feed?limit=2&cursor=" + next2)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].content", equalTo("p1")))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }
}

