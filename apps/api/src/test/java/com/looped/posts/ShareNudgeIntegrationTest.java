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
import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app",
        "posts.share-nudge.delay-minutes=0",
        "posts.share-nudge.max-served-per-day=2",
        "posts.share-nudge.min-minutes-between-serves=0"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class ShareNudgeIntegrationTest extends PostgresTestBase {

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
    void feed_serves_share_nudge_once_for_author() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Nudge Co', 'nudge.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_completed_at) VALUES (?,?,?, now()) RETURNING id",
                Long.class,
                "uid-nudge-author",
                "author",
                companyId
        );
        long principalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, userId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'Nudge Co') RETURNING id", Long.class);
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, likes_count, comments_count, created_at) VALUES (?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                userId,
                principalId,
                companyId,
                communityId,
                "low traction post",
                0,
                0,
                Timestamp.from(Instant.now().minusSeconds(300))
        );

        String auth = "Bearer " + token("uid-nudge-author");

        mockMvc.perform(get("/v1/feed?mode=new&limit=20").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id", equalTo((int) postId)))
                .andExpect(jsonPath("$.items[0].shareNudge.id", notNullValue()))
                .andExpect(jsonPath("$.items[0].shareNudge.messageKey", equalTo("share_nudge.low_traction")));

        mockMvc.perform(get("/v1/feed?mode=new&limit=20").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id", equalTo((int) postId)))
                .andExpect(jsonPath("$.items[0].shareNudge", nullValue()));

        OffsetDateTime firstServedAt = jdbc.queryForObject(
                "SELECT first_served_at FROM post_share_nudge_state WHERE post_id = ? AND user_id = ?",
                OffsetDateTime.class,
                postId,
                userId
        );
        org.junit.jupiter.api.Assertions.assertNotNull(firstServedAt);
    }

    @Test
    void feed_does_not_serve_share_nudge_when_engagement_above_threshold() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Nudge Co2', 'nudge2.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_completed_at) VALUES (?,?,?, now()) RETURNING id",
                Long.class,
                "uid-nudge-author-2",
                "author2",
                companyId
        );
        long principalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, userId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'Nudge Co2') RETURNING id", Long.class);
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, likes_count, comments_count, created_at) VALUES (?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                userId,
                principalId,
                companyId,
                communityId,
                "already traction",
                1,
                0,
                Timestamp.from(Instant.now().minusSeconds(300))
        );

        String auth = "Bearer " + token("uid-nudge-author-2");

        mockMvc.perform(get("/v1/feed?mode=new&limit=20").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id", equalTo((int) postId)))
                .andExpect(jsonPath("$.items[0].shareNudge", nullValue()));
    }

    @Test
    void dismiss_and_share_tap_are_author_only_and_idempotent() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Nudge Co3', 'nudge3.co') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_completed_at) VALUES (?,?,?, now()) RETURNING id",
                Long.class,
                "uid-nudge-author-3",
                "author3",
                companyId
        );
        long authorPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long otherId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_completed_at) VALUES (?,?,?, now()) RETURNING id",
                Long.class,
                "uid-nudge-other",
                "other",
                companyId
        );
        jdbc.update("INSERT INTO principals(kind, user_id) VALUES ('user', ?)", otherId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'Nudge Co3') RETURNING id", Long.class);
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, likes_count, comments_count, created_at) VALUES (?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                authorId,
                authorPrincipalId,
                companyId,
                communityId,
                "mutation test",
                0,
                0,
                Timestamp.from(Instant.now().minusSeconds(300))
        );

        String authorAuth = "Bearer " + token("uid-nudge-author-3");
        String otherAuth = "Bearer " + token("uid-nudge-other");

        mockMvc.perform(post("/v1/posts/" + postId + "/share-nudge/dismiss").header("Authorization", otherAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("forbidden")));

        mockMvc.perform(post("/v1/posts/" + postId + "/share-nudge/dismiss").header("Authorization", authorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed_at", notNullValue()));

        OffsetDateTime firstDismissed = jdbc.queryForObject(
                "SELECT dismissed_at FROM post_share_nudge_state WHERE post_id = ? AND user_id = ?",
                OffsetDateTime.class,
                postId,
                authorId
        );

        mockMvc.perform(post("/v1/posts/" + postId + "/share-nudge/dismiss").header("Authorization", authorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed_at", notNullValue()));

        OffsetDateTime secondDismissed = jdbc.queryForObject(
                "SELECT dismissed_at FROM post_share_nudge_state WHERE post_id = ? AND user_id = ?",
                OffsetDateTime.class,
                postId,
                authorId
        );
        org.junit.jupiter.api.Assertions.assertEquals(firstDismissed, secondDismissed);

        mockMvc.perform(post("/v1/posts/" + postId + "/share-nudge/share-tap").header("Authorization", authorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.share_payload.deep_link", equalTo("looped://posts/" + postId)))
                .andExpect(jsonPath("$.share_payload.requires_auth", equalTo(true)));

        OffsetDateTime firstTapped = jdbc.queryForObject(
                "SELECT share_tapped_at FROM post_share_nudge_state WHERE post_id = ? AND user_id = ?",
                OffsetDateTime.class,
                postId,
                authorId
        );

        mockMvc.perform(post("/v1/posts/" + postId + "/share-nudge/share-tap").header("Authorization", authorAuth))
                .andExpect(status().isOk());

        OffsetDateTime secondTapped = jdbc.queryForObject(
                "SELECT share_tapped_at FROM post_share_nudge_state WHERE post_id = ? AND user_id = ?",
                OffsetDateTime.class,
                postId,
                authorId
        );
        org.junit.jupiter.api.Assertions.assertEquals(firstTapped, secondTapped);
    }
}
