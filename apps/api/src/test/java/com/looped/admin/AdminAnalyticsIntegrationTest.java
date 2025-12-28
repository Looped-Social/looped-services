package com.looped.admin;

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

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class AdminAnalyticsIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AdminUsersRepository admins;

    private String token(String sub, String email) {
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
    void community_leaderboard_counts_metrics() throws Exception {
        admins.insert(null, "analytics@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_REPORTS));
        String auth = "Bearer " + token("admin-analytics", "analytics@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme', 'acme.com') RETURNING id",
                Long.class);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('sector', 'Design') RETURNING id",
                Long.class);
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-author", "author", companyId);
        long likerId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-liker", "liker", companyId);
        long authorPrincipal = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, authorId);
        long likerPrincipal = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, likerId);
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, communityId, "hello");

        jdbc.update("INSERT INTO post_likes(liker_principal_id, post_id) VALUES (?,?)", likerPrincipal, postId);
        jdbc.update("INSERT INTO post_shares(sharer_principal_id, post_id) VALUES (?,?)", likerPrincipal, postId);
        jdbc.update("INSERT INTO community_follows(user_id, community_id) VALUES (?,?)", likerId, communityId);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                likerId, communityId, "manual", true);

        mockMvc.perform(get("/v1/admin/analytics/communities/leaderboard?metric=shares")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id", equalTo((int) communityId)))
                .andExpect(jsonPath("$.items[0].shares_count", equalTo(1)))
                .andExpect(jsonPath("$.items[0].likes_count", equalTo(1)))
                .andExpect(jsonPath("$.items[0].followers_count", equalTo(1)))
                .andExpect(jsonPath("$.items[0].verifications_count", equalTo(1)))
                .andExpect(jsonPath("$.items[0].accounts_total", equalTo(2)));
    }

    @Test
    void user_stats_counts_deleted() throws Exception {
        admins.insert(null, "reports@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_REPORTS));
        String auth = "Bearer " + token("admin-reports", "reports@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('UserCo', 'userco.com') RETURNING id",
                Long.class);
        long activeId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-active", "active", companyId);
        long deletedId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-deleted", "deleted", companyId);
        jdbc.update("UPDATE users SET deleted_at = now(), deleted_by = ? WHERE id = ?", deletedId, deletedId);

        mockMvc.perform(get("/v1/admin/analytics/users")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_users", equalTo(1)))
                .andExpect(jsonPath("$.new_users", equalTo(1)))
                .andExpect(jsonPath("$.deleted_users", equalTo(1)));
    }
}
