package com.looped.posts;

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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class RepostsIntegrationTest extends PostgresTestBase {

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
    void repost_is_idempotent_and_updates_count_and_viewer_state() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-author-repost", "author", companyId);
        long authorPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long viewerId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-viewer-repost", "viewer", companyId);
        jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, viewerId);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, "hello");

        String auth = "Bearer " + token("uid-viewer-repost");

        mockMvc.perform(put("/v1/posts/" + postId + "/repost").header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repost_count").doesNotExist())
                .andExpect(jsonPath("$.viewer_has_reposted", equalTo(true)));
        assertEquals(1, jdbc.queryForObject("SELECT repost_count FROM posts WHERE id = ?", Integer.class, postId));

        mockMvc.perform(put("/v1/posts/" + postId + "/repost").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repost_count").doesNotExist())
                .andExpect(jsonPath("$.viewer_has_reposted", equalTo(true)));
        assertEquals(1, jdbc.queryForObject("SELECT repost_count FROM posts WHERE id = ?", Integer.class, postId));

        mockMvc.perform(get("/v1/posts/" + postId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repost_count").doesNotExist())
                .andExpect(jsonPath("$.viewer_has_reposted", equalTo(true)));

        mockMvc.perform(delete("/v1/posts/" + postId + "/repost").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repost_count").doesNotExist())
                .andExpect(jsonPath("$.viewer_has_reposted", equalTo(false)));
        assertEquals(0, jdbc.queryForObject("SELECT repost_count FROM posts WHERE id = ?", Integer.class, postId));

        mockMvc.perform(delete("/v1/posts/" + postId + "/repost").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repost_count").doesNotExist())
                .andExpect(jsonPath("$.viewer_has_reposted", equalTo(false)));
        assertEquals(0, jdbc.queryForObject("SELECT repost_count FROM posts WHERE id = ?", Integer.class, postId));

        mockMvc.perform(get("/v1/posts/" + postId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repost_count").doesNotExist())
                .andExpect(jsonPath("$.viewer_has_reposted", equalTo(false)));
    }

    @Test
    void repost_disallows_self_repost() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme2','acme2.com') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-self-repost", "self", companyId);
        long principalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, userId);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, userId, principalId, companyId, "hello");

        String auth = "Bearer " + token("uid-self-repost");

        mockMvc.perform(put("/v1/posts/" + postId + "/repost").header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", equalTo("self_repost_not_allowed")));
    }

    @Test
    void repost_allows_cross_company_user() throws Exception {
        long companyA = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('AcmeA','acmea.com') RETURNING id", Long.class);
        long companyB = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('AcmeB','acmeb.com') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-cross-author", "authora", companyA);
        long authorPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long viewerId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-cross-viewer", "viewerb", companyB);
        jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, viewerId);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyA, "cross company");

        String auth = "Bearer " + token("uid-cross-viewer");

        mockMvc.perform(put("/v1/posts/" + postId + "/repost").header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.viewer_has_reposted", equalTo(true)));
        assertEquals(1, jdbc.queryForObject("SELECT repost_count FROM posts WHERE id = ?", Integer.class, postId));
    }

    @Test
    void feed_includes_repost_banner_for_followed_users() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme3','acme3.com') RETURNING id", Long.class);
        long viewerId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-banner-viewer", "viewer", companyId);
        long viewerPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, viewerId);

        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-banner-author", "author", companyId);
        long authorPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);

        Instant base = Instant.now();
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, "hello", Timestamp.from(base.minusSeconds(60)));

        long reposterA = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-reposter-a", "a", companyId);
        long reposterB = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-reposter-b", "b", companyId);
        long reposterC = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-reposter-c", "c", companyId);

        long principalA = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, reposterA);
        long principalB = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, reposterB);
        long principalC = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, reposterC);

        jdbc.update("INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)", viewerPrincipalId, principalA);
        jdbc.update("INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)", viewerPrincipalId, principalB);
        jdbc.update("INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)", viewerPrincipalId, principalC);

        jdbc.update("INSERT INTO post_reposts(reposter_principal_id, post_id, created_at) VALUES (?,?,?)", principalA, postId, Timestamp.from(base.minusSeconds(30)));
        jdbc.update("INSERT INTO post_reposts(reposter_principal_id, post_id, created_at) VALUES (?,?,?)", principalB, postId, Timestamp.from(base.minusSeconds(20)));
        jdbc.update("INSERT INTO post_reposts(reposter_principal_id, post_id, created_at) VALUES (?,?,?)", principalC, postId, Timestamp.from(base.minusSeconds(10)));
        jdbc.update("UPDATE posts SET repost_count = 3 WHERE id = ?", postId);

        String auth = "Bearer " + token("uid-banner-viewer");

        var r1 = mockMvc.perform(get("/v1/feed?limit=1").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].reposted_by_followed_users_count", equalTo(3)))
                .andExpect(jsonPath("$.items[0].reposted_by_followed_users", hasSize(2)))
                .andExpect(jsonPath("$.items[0].reposted_by_followed_users[0].username", equalTo("c")))
                .andExpect(jsonPath("$.items[0].reposted_by_followed_users[1].username", equalTo("b")))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String next = r1.getResponse().getContentAsString().replaceAll(".*\"next_cursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/v1/feed?limit=1&cursor=" + next).header("Authorization", auth))
                .andExpect(status().isOk());
    }
}
