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
import java.util.UUID;

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
class MyContentIntegrationTest extends PostgresTestBase {

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
    void me_content_matches_user_content_shape_and_paginates() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long meId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-me-content", "me", companyId);
        long mePrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, meId);

        Instant base = Instant.now();
        long myPost1 = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, meId, mePrincipalId, companyId, "p1", Timestamp.from(base.minusSeconds(100)));
        jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, meId, mePrincipalId, companyId, "p2", Timestamp.from(base.minusSeconds(50)));

        long hostId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-host-content", "host", companyId);
        long hostPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, hostId);
        long hostPost = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, hostId, hostPrincipalId, companyId, "host", Timestamp.from(base.minusSeconds(200)));
        jdbc.update("INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?,?)",
                hostPost, meId, mePrincipalId, companyId, "r1", Timestamp.from(base.minusSeconds(80)));
        jdbc.update("INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?,?)",
                hostPost, meId, mePrincipalId, companyId, "r2", Timestamp.from(base.minusSeconds(40)));

        String auth = "Bearer " + token("uid-me-content");

        var r1 = mockMvc.perform(get("/v1/users/me/content?limit=3").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].type", equalTo("reply")))
                .andExpect(jsonPath("$.items[0].reply.content", equalTo("r2")))
                .andExpect(jsonPath("$.items[1].type", equalTo("post")))
                .andExpect(jsonPath("$.items[2].type", equalTo("reply")))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String cursor = r1.getResponse().getContentAsString().replaceAll(".*\"next_cursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/v1/users/me/content?cursor=" + cursor + "&limit=3").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].type", equalTo("post")))
                .andExpect(jsonPath("$.items[0].post.id", equalTo((int) myPost1)))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void me_content_includes_poll_for_posts_and_reply_post_previews() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('AcmePoll','acmepoll.com') RETURNING id", Long.class);
        long meId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-me-poll-content", "mepoll", companyId);
        long mePrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, meId);

        long hostId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-me-poll-host", "hostpoll", companyId);
        long hostPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, hostId);

        Instant base = Instant.now();

        long myPollPostId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, meId, mePrincipalId, companyId, "my poll post", Timestamp.from(base.minusSeconds(60))
        );
        long myPollId = jdbc.queryForObject(
                "INSERT INTO polls(post_id, question, max_selections, closes_at) VALUES (?,?,?, now() + interval '7 days') RETURNING id",
                Long.class, myPollPostId, "My poll?", 1
        );
        jdbc.update("INSERT INTO poll_options(poll_id, text, sort_order) VALUES (?,?,?)", myPollId, "M1", 0);
        jdbc.update("INSERT INTO poll_options(poll_id, text, sort_order) VALUES (?,?,?)", myPollId, "M2", 1);

        long hostPollPostId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, hostId, hostPrincipalId, companyId, "host poll post", Timestamp.from(base.minusSeconds(120))
        );
        long hostPollId = jdbc.queryForObject(
                "INSERT INTO polls(post_id, question, max_selections, closes_at) VALUES (?,?,?, now() + interval '7 days') RETURNING id",
                Long.class, hostPollPostId, "Host poll?", 1
        );
        jdbc.update("INSERT INTO poll_options(poll_id, text, sort_order) VALUES (?,?,?)", hostPollId, "H1", 0);
        jdbc.update("INSERT INTO poll_options(poll_id, text, sort_order) VALUES (?,?,?)", hostPollId, "H2", 1);

        jdbc.update("INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?,?)",
                hostPollPostId, meId, mePrincipalId, companyId, "reply on host poll", Timestamp.from(base.minusSeconds(40)));

        String auth = "Bearer " + token("uid-me-poll-content");

        mockMvc.perform(get("/v1/users/me/content?limit=10&include_post_preview=true").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].type", equalTo("reply")))
                .andExpect(jsonPath("$.items[0].post.id", equalTo((int) hostPollPostId)))
                .andExpect(jsonPath("$.items[0].post.poll.question", equalTo("Host poll?")))
                .andExpect(jsonPath("$.items[0].post.poll.options", hasSize(2)))
                .andExpect(jsonPath("$.items[0].post.viewer_capabilities.canVote", equalTo(true)))
                .andExpect(jsonPath("$.items[1].type", equalTo("post")))
                .andExpect(jsonPath("$.items[1].post.id", equalTo((int) myPollPostId)))
                .andExpect(jsonPath("$.items[1].post.poll.question", equalTo("My poll?")))
                .andExpect(jsonPath("$.items[1].post.poll.options", hasSize(2)))
                .andExpect(jsonPath("$.items[1].post.viewer_capabilities.canVote", equalTo(true)));
    }

    @Test
    void me_content_includes_view_count_for_my_posts_only() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('AcmeViews','acmeviews.com') RETURNING id", Long.class);
        long meId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-me-content-views", "meviews", companyId);
        long mePrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, meId);

        long viewerAId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-me-content-viewer-a", "viewera", companyId);
        long viewerAPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, viewerAId);
        long viewerBId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-me-content-viewer-b", "viewerb", companyId);
        long viewerBPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, viewerBId);

        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?, now()) RETURNING id",
                Long.class,
                meId,
                mePrincipalId,
                companyId,
                "my viewed post"
        );

        Instant occurred = Instant.now();
        jdbc.update(
                "INSERT INTO telemetry_events(user_id, principal_id, session_id, event_id, type, occurred_at, post_id, payload) VALUES (?,?,?,?,?,?,?, '{}'::jsonb)",
                viewerAId, viewerAPrincipalId, UUID.randomUUID(), UUID.randomUUID(), "post_open", Timestamp.from(occurred), postId
        );
        jdbc.update(
                "INSERT INTO telemetry_events(user_id, principal_id, session_id, event_id, type, occurred_at, post_id, payload) VALUES (?,?,?,?,?,?,?, '{}'::jsonb)",
                viewerAId, viewerAPrincipalId, UUID.randomUUID(), UUID.randomUUID(), "post_open", Timestamp.from(occurred.plusSeconds(5)), postId
        );
        jdbc.update(
                "INSERT INTO telemetry_events(user_id, principal_id, session_id, event_id, type, occurred_at, post_id, payload) VALUES (?,?,?,?,?,?,?, '{}'::jsonb)",
                viewerBId, viewerBPrincipalId, UUID.randomUUID(), UUID.randomUUID(), "post_open", Timestamp.from(occurred.plusSeconds(10)), postId
        );

        String auth = "Bearer " + token("uid-me-content-views");
        mockMvc.perform(get("/v1/users/me/content?limit=10").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].type", equalTo("post")))
                .andExpect(jsonPath("$.items[0].post.id", equalTo((int) postId)))
                .andExpect(jsonPath("$.items[0].post.view_count", equalTo(2)))
                .andExpect(jsonPath("$.items[0].post.viewCount", equalTo(2)));
    }
}
