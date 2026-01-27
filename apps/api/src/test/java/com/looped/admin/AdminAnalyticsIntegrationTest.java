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
import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
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
                "INSERT INTO communities(kind, name) VALUES ('company', 'Design') RETURNING id",
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

    @Test
    void active_users_kpi_counts_action_based_dau_and_mau() throws Exception {
        admins.insert(null, "kpi@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_REPORTS));
        String auth = "Bearer " + token("admin-kpi", "kpi@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme', 'acme.com') RETURNING id",
                Long.class);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Design') RETURNING id",
                Long.class);

        long user1 = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-u1", "u1", companyId);
        long user2 = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-u2", "u2", companyId);
        long p1 = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, user1);
        long p2 = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, user2);

        OffsetDateTime d1 = OffsetDateTime.parse("2026-01-01T12:00:00Z");
        OffsetDateTime d2 = OffsetDateTime.parse("2026-01-02T12:00:00Z");
        OffsetDateTime d3 = OffsetDateTime.parse("2026-01-03T12:00:00Z");

        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, created_at) VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class, user1, p1, companyId, communityId, "hello", d1);
        jdbc.update("INSERT INTO post_likes(liker_principal_id, post_id, created_at) VALUES (?,?,?)", p2, postId, d2);
        jdbc.update("INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?,?)",
                postId, user1, p1, companyId, "c1", d2);
        jdbc.update("INSERT INTO community_follows(user_id, community_id, created_at) VALUES (?,?,?)", user2, communityId, d3);

        mockMvc.perform(get("/v1/admin/analytics/kpis/active-users?from=2026-01-01&to=2026-01-03")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].day", equalTo("2026-01-01")))
                .andExpect(jsonPath("$.items[0].dau", equalTo(1)))
                .andExpect(jsonPath("$.items[0].mau_30d", equalTo(1)))
                .andExpect(jsonPath("$.items[1].day", equalTo("2026-01-02")))
                .andExpect(jsonPath("$.items[1].dau", equalTo(2)))
                .andExpect(jsonPath("$.items[1].mau_30d", equalTo(2)))
                .andExpect(jsonPath("$.items[2].day", equalTo("2026-01-03")))
                .andExpect(jsonPath("$.items[2].dau", equalTo(1)))
                .andExpect(jsonPath("$.items[2].mau_30d", equalTo(2)));
    }

    @Test
    void community_daily_kpi_counts_posts_comments_likes_shares() throws Exception {
        admins.insert(null, "kpi2@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_REPORTS));
        String auth = "Bearer " + token("admin-kpi2", "kpi2@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme2', 'acme2.com') RETURNING id",
                Long.class);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('school', 'StateU') RETURNING id",
                Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-a", "a", companyId);
        long principalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, userId);

        OffsetDateTime day1 = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        OffsetDateTime day2 = OffsetDateTime.parse("2026-01-02T10:00:00Z");
        OffsetDateTime day3 = OffsetDateTime.parse("2026-01-03T10:00:00Z");

        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, created_at) VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class, userId, principalId, companyId, communityId, "p1", day1);
        jdbc.update("INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?,?)",
                postId, userId, principalId, companyId, "c1", day2);
        jdbc.update("INSERT INTO post_likes(liker_principal_id, post_id, created_at) VALUES (?,?,?)", principalId, postId, day2);
        jdbc.update("INSERT INTO post_shares(sharer_principal_id, post_id, created_at) VALUES (?,?,?)", principalId, postId, day3);

        mockMvc.perform(get("/v1/admin/analytics/kpis/communities/daily?communityId=" + communityId + "&from=2026-01-01&to=2026-01-03")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.community_id", equalTo((int) communityId)))
                .andExpect(jsonPath("$.items[0].day", equalTo("2026-01-01")))
                .andExpect(jsonPath("$.items[0].posts_count", equalTo(1)))
                .andExpect(jsonPath("$.items[1].day", equalTo("2026-01-02")))
                .andExpect(jsonPath("$.items[1].comments_count", equalTo(1)))
                .andExpect(jsonPath("$.items[1].post_likes_count", equalTo(1)))
                .andExpect(jsonPath("$.items[2].day", equalTo("2026-01-03")))
                .andExpect(jsonPath("$.items[2].post_shares_count", equalTo(1)))
                .andExpect(jsonPath("$.items[1].comment_to_post_ratio", greaterThanOrEqualTo(0.0)));
    }

    @Test
    void community_retention_kpi_uses_verification_for_non_specialization() throws Exception {
        admins.insert(null, "kpi3@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_REPORTS));
        String auth = "Bearer " + token("admin-kpi3", "kpi3@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme3', 'acme3.com') RETURNING id",
                Long.class);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('school', 'StateU2') RETURNING id",
                Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-r", "r", companyId);
        long principalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, userId);

        OffsetDateTime cohortDay = OffsetDateTime.parse("2026-01-01T09:00:00Z");
        OffsetDateTime dayPlus1 = OffsetDateTime.parse("2026-01-02T09:00:00Z");

        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?,?)",
                userId, communityId, "manual", true, cohortDay);
        jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, created_at) VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class, userId, principalId, companyId, communityId, "after", dayPlus1);

        mockMvc.perform(get("/v1/admin/analytics/kpis/communities/retention?communityId=" + communityId + "&from=2026-01-01&to=2026-01-01")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].cohort_day", equalTo("2026-01-01")))
                .andExpect(jsonPath("$.items[0].cohort_size", equalTo(1)))
                .andExpect(jsonPath("$.items[0].retained_d1", equalTo(1)))
                .andExpect(jsonPath("$.items[0].retained_d7", equalTo(0)))
                .andExpect(jsonPath("$.items[0].retained_d30", equalTo(0)));
    }

    @Test
    void growth_users_daily_returns_time_series() throws Exception {
        admins.insert(null, "growth@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_REPORTS));
        String auth = "Bearer " + token("admin-growth", "growth@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('GrowthCo', 'growth.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id, created_at) VALUES (?,?,?,?)",
                "uid-g1", "g1", companyId, OffsetDateTime.parse("2026-01-01T01:00:00Z"));
        long g2 = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, created_at) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-g2", "g2", companyId, OffsetDateTime.parse("2026-01-02T01:00:00Z"));
        jdbc.update("UPDATE users SET deleted_at = ? WHERE id = ?", OffsetDateTime.parse("2026-01-03T01:00:00Z"), g2);

        mockMvc.perform(get("/v1/admin/analytics/kpis/growth/users/daily?from=2026-01-01&to=2026-01-03")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].day", equalTo("2026-01-01")))
                .andExpect(jsonPath("$.items[0].new_users", equalTo(1)))
                .andExpect(jsonPath("$.items[1].day", equalTo("2026-01-02")))
                .andExpect(jsonPath("$.items[1].new_users", equalTo(1)))
                .andExpect(jsonPath("$.items[2].day", equalTo("2026-01-03")))
                .andExpect(jsonPath("$.items[2].deleted_users", equalTo(1)));
    }

    @Test
    void content_creation_daily_returns_creator_rate() throws Exception {
        admins.insert(null, "content@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_REPORTS));
        String auth = "Bearer " + token("admin-content", "content@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ContentCo', 'content.com') RETURNING id",
                Long.class);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Eng') RETURNING id",
                Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-c", "c", companyId);
        long principalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, userId);
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, created_at) VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class, userId, principalId, companyId, communityId, "p", OffsetDateTime.parse("2026-01-01T10:00:00Z"));
        jdbc.update("INSERT INTO post_likes(liker_principal_id, post_id, created_at) VALUES (?,?,?)",
                principalId, postId, OffsetDateTime.parse("2026-01-01T11:00:00Z"));

        mockMvc.perform(get("/v1/admin/analytics/kpis/content/creation/daily?from=2026-01-01&to=2026-01-01")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].day", equalTo("2026-01-01")))
                .andExpect(jsonPath("$.items[0].creators", equalTo(1)))
                .andExpect(jsonPath("$.items[0].active_users", equalTo(1)))
                .andExpect(jsonPath("$.items[0].creator_rate", equalTo(1.0)));
    }

    @Test
    void repeat_offenders_counts_user_bans_and_post_removals() throws Exception {
        admins.insert(null, "mods@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_REPORTS));
        String auth = "Bearer " + token("admin-mods", "mods@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ModCo', 'mod.com') RETURNING id",
                Long.class);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Mod') RETURNING id",
                Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-m", "m", companyId);
        long principalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, userId);

        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, userId, principalId, companyId, communityId, "bad");
        jdbc.update("UPDATE posts SET removed_at = ?, removed_reason = 'mod' WHERE id = ?",
                OffsetDateTime.parse("2026-01-02T00:00:00Z"), postId);
        jdbc.update("INSERT INTO user_bans(user_id, created_at) VALUES (?, ?)",
                userId, OffsetDateTime.parse("2026-01-03T00:00:00Z"));

        mockMvc.perform(get("/v1/admin/analytics/kpis/moderation/repeat-offenders?from=2026-01-01&to=2026-01-03")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unique_violators", equalTo(1)))
                .andExpect(jsonPath("$.repeat_offenders", equalTo(1)));
    }
}
