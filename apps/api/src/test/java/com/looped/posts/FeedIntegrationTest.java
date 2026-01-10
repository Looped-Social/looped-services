package com.looped.posts;

import com.looped.auth.TestSecurityConfig;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
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
    void feed_returns_popular_posts_with_pagination() throws Exception {
        long acmeId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id", Long.class,
                "uid-feed", "erin", acmeId);
        long principalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, userId);
        long commA = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'Acme') RETURNING id", Long.class);
        long commB = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('sector', 'Finance') RETURNING id", Long.class);

        Instant base = Instant.now();
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, likes_count, comments_count, share_count, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                userId, principalId, acmeId, commA, "p1", 1, 1, 0, Timestamp.from(base.minusSeconds(180))
        ); // score 3
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, likes_count, comments_count, share_count, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                userId, principalId, acmeId, commB, "p2", 3, 0, 0, Timestamp.from(base.minusSeconds(120))
        ); // score 6
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, likes_count, comments_count, share_count, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                userId, principalId, acmeId, commA, "p3", 2, 2, 1, Timestamp.from(base.minusSeconds(60))
        ); // score 7

        String auth = "Bearer " + token("uid-feed");

        var r1 = mockMvc.perform(get("/v1/feed?limit=2")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].content", equalTo("p3")))
                .andExpect(jsonPath("$.items[1].content", equalTo("p2")))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String next = r1.getResponse().getContentAsString().replaceAll(".*\"next_cursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/v1/feed?limit=2&cursor=" + next)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].content", equalTo("p1")))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void feed_filters_by_community_id() throws Exception {
        long acmeId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id", Long.class,
                "uid-loop-feed", "lena", acmeId);
        long principalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, userId);

        long commA = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('sector', ?) RETURNING id", Long.class, "Finance");
        long commB = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('sector', ?) RETURNING id", Long.class, "Product");

        Instant base = Instant.now();
        jdbc.update("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, created_at) VALUES (?,?,?,?,?,?)",
                userId, principalId, acmeId, commA, "a1", Timestamp.from(base.minusSeconds(120)));
        jdbc.update("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, created_at) VALUES (?,?,?,?,?,?)",
                userId, principalId, acmeId, commB, "b1", Timestamp.from(base.minusSeconds(90)));
        jdbc.update("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, created_at) VALUES (?,?,?,?,?,?)",
                userId, principalId, acmeId, commA, "a2", Timestamp.from(base.minusSeconds(60)));

        String auth = "Bearer " + token("uid-loop-feed");

        mockMvc.perform(get("/v1/feed?communityId=" + commA + "&limit=10")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].content", containsInAnyOrder("a1", "a2")));
    }

    @Test
    void feed_excludes_blocked_principals() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('BlockFeed','blockfeed.co') RETURNING id", Long.class);
        long viewerUserId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-viewer", "viewer", companyId);
        long viewerPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, viewerUserId);

        long blockedUserId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-blocked", "blocked", companyId);
        long blockedPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, blockedUserId);

        jdbc.update("INSERT INTO principal_blocks(blocker_principal_id, blocked_principal_id) VALUES (?,?)", viewerPrincipalId, blockedPrincipalId);

        long comm = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'BlockFeed') RETURNING id", Long.class);
        Instant base = Instant.now();
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, created_at) VALUES (?,?,?,?,?,?)",
                viewerUserId, viewerPrincipalId, companyId, comm, "viewer-post", Timestamp.from(base.minusSeconds(60))
        );
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, created_at) VALUES (?,?,?,?,?,?)",
                blockedUserId, blockedPrincipalId, companyId, comm, "blocked-post", Timestamp.from(base.minusSeconds(30))
        );

        String auth = "Bearer " + token("uid-viewer");
        mockMvc.perform(get("/v1/feed?mode=new&limit=10")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].content", equalTo("viewer-post")))
                .andExpect(jsonPath("$.items[*].content", not(hasItem("blocked-post"))));
    }
}
