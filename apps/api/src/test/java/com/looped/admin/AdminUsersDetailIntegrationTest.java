package com.looped.admin;

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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class AdminUsersDetailIntegrationTest extends PostgresTestBase {

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

    private String adminAuthWithBanPermission() {
        admins.insert(null, "admin-users-detail@looped.com", "admin", "active",
                List.of(AdminPermissions.BAN_USER));
        return "Bearer " + token("admin-users-detail", "admin-users-detail@looped.com");
    }

    @Test
    void detail_returns_200_for_user_with_complete_data() throws Exception {
        String auth = adminAuthWithBanPermission();

        long targetUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, email, company_id) VALUES (?,?,?,1) RETURNING id",
                Long.class, "uid-detail-complete", "detailcomplete", "detail-complete@looped.com"
        );
        long targetPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, targetUserId
        );
        long reporterUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, email, company_id) VALUES (?,?,?,1) RETURNING id",
                Long.class, "uid-detail-reporter", "detailreporter", "detail-reporter@looped.com"
        );
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, targetUserId, targetPrincipalId, 1L, "post for detail stats"
        );

        jdbc.update("INSERT INTO reports(target_type, target_id, reporter_id, reason, status) VALUES ('user', ?, ?, 'spam', 'open')",
                targetUserId, reporterUserId);
        jdbc.update("INSERT INTO reports(target_type, target_id, reporter_id, reason, status) VALUES ('user', ?, ?, 'abuse', 'resolved')",
                targetUserId, reporterUserId);
        jdbc.update("INSERT INTO reports(target_type, target_id, reporter_id, reason, status) VALUES ('user', ?, ?, 'other', 'dismissed')",
                targetUserId, reporterUserId);

        jdbc.update("INSERT INTO reports(target_type, target_id, reporter_id, reason, status) VALUES ('post', ?, ?, 'spam', 'open')",
                postId, reporterUserId);
        jdbc.update("INSERT INTO reports(target_type, target_id, reporter_id, reason, status) VALUES ('post', ?, ?, 'abuse', 'resolved')",
                postId, reporterUserId);
        jdbc.update("INSERT INTO reports(target_type, target_id, reporter_id, reason, status) VALUES ('post', ?, ?, 'other', 'dismissed')",
                postId, reporterUserId);

        jdbc.update("INSERT INTO reports(target_type, target_id, reporter_id, reason, status) VALUES ('user', ?, ?, 'file-open', 'open')",
                reporterUserId, targetUserId);
        jdbc.update("INSERT INTO reports(target_type, target_id, reporter_id, reason, status) VALUES ('user', ?, ?, 'file-resolved', 'resolved')",
                reporterUserId, targetUserId);
        jdbc.update("INSERT INTO reports(target_type, target_id, reporter_id, reason, status) VALUES ('user', ?, ?, 'file-dismissed', 'dismissed')",
                reporterUserId, targetUserId);

        jdbc.update(
                "INSERT INTO user_bans(user_id, reason, created_by, created_at, expires_at) VALUES (?,?,?,?,?)",
                targetUserId, "ban reason", null, OffsetDateTime.now().minusMinutes(5), OffsetDateTime.now().plusDays(3)
        );

        mockMvc.perform(get("/v1/admin/users/" + targetUserId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) targetUserId)))
                .andExpect(jsonPath("$.handle", equalTo("detailcomplete")))
                .andExpect(jsonPath("$.ban.status", equalTo("banned")))
                .andExpect(jsonPath("$.ban.reason", equalTo("ban reason")))
                .andExpect(jsonPath("$.moderation_stats.posts_total", equalTo(1)))
                .andExpect(jsonPath("$.moderation_stats.posts_removed_total", equalTo(0)))
                .andExpect(jsonPath("$.moderation_stats.reports_against_user_total", equalTo(3)))
                .andExpect(jsonPath("$.moderation_stats.reports_against_user_open", equalTo(1)))
                .andExpect(jsonPath("$.moderation_stats.reports_against_user_resolved", equalTo(1)))
                .andExpect(jsonPath("$.moderation_stats.reports_against_user_dismissed", equalTo(1)))
                .andExpect(jsonPath("$.moderation_stats.reports_against_posts_total", equalTo(3)))
                .andExpect(jsonPath("$.moderation_stats.reports_against_posts_open", equalTo(1)))
                .andExpect(jsonPath("$.moderation_stats.reports_against_posts_resolved", equalTo(1)))
                .andExpect(jsonPath("$.moderation_stats.reports_against_posts_dismissed", equalTo(1)))
                .andExpect(jsonPath("$.moderation_stats.reports_filed_total", equalTo(3)))
                .andExpect(jsonPath("$.moderation_stats.reports_filed_open", equalTo(1)))
                .andExpect(jsonPath("$.moderation_stats.reports_filed_resolved", equalTo(1)))
                .andExpect(jsonPath("$.moderation_stats.reports_filed_dismissed", equalTo(1)));
    }

    @Test
    void detail_returns_200_when_optional_related_data_is_missing() throws Exception {
        String auth = adminAuthWithBanPermission();

        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,1) RETURNING id",
                Long.class, "uid-detail-missing-optional", "detailmissing"
        );

        mockMvc.perform(get("/v1/admin/users/" + userId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) userId)))
                .andExpect(jsonPath("$.ban.status", equalTo("none")))
                .andExpect(jsonPath("$.ban.reason", nullValue()))
                .andExpect(jsonPath("$.ban.created_at", nullValue()))
                .andExpect(jsonPath("$.ban.expires_at", nullValue()))
                .andExpect(jsonPath("$.ban.created_by", nullValue()))
                .andExpect(jsonPath("$.moderation_stats.posts_total", equalTo(0)))
                .andExpect(jsonPath("$.moderation_stats.posts_removed_total", equalTo(0)))
                .andExpect(jsonPath("$.moderation_stats.reports_against_user_total", equalTo(0)))
                .andExpect(jsonPath("$.moderation_stats.reports_against_posts_total", equalTo(0)))
                .andExpect(jsonPath("$.moderation_stats.reports_filed_total", equalTo(0)));
    }

    @Test
    void detail_returns_200_for_soft_deleted_or_disabled_user_with_partial_relations() throws Exception {
        String auth = adminAuthWithBanPermission();

        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,1) RETURNING id",
                Long.class, "uid-detail-deleted-disabled", "detaildeleted"
        );
        jdbc.update(
                "UPDATE users SET disabled_at = now(), disabled_reason = 'fraud', deleted_at = now(), deleted_by = ?, deleted_source = 'admin' WHERE id = ?",
                userId, userId
        );

        mockMvc.perform(get("/v1/admin/users/" + userId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) userId)))
                .andExpect(jsonPath("$.account_status", equalTo("deleted")))
                .andExpect(jsonPath("$.disabled_reason", equalTo("fraud")))
                .andExpect(jsonPath("$.deleted_at", notNullValue()))
                .andExpect(jsonPath("$.ban.status", equalTo("none")))
                .andExpect(jsonPath("$.moderation_stats.posts_total", equalTo(0)));
    }

    @Test
    void detail_returns_404_for_non_existent_user() throws Exception {
        String auth = adminAuthWithBanPermission();

        mockMvc.perform(get("/v1/admin/users/999999")
                        .header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", equalTo("not_found")));
    }

    @Test
    void detail_regression_duplicate_principals_does_not_500() throws Exception {
        String auth = adminAuthWithBanPermission();

        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,1) RETURNING id",
                Long.class, "uid-detail-regression", "detailregress"
        );
        long firstPrincipal = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, userId
        );

        jdbc.execute("ALTER TABLE principals DROP CONSTRAINT IF EXISTS principals_user_id_key");
        try {
            long secondPrincipal = jdbc.queryForObject(
                    "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                    Long.class, userId
            );
            jdbc.update(
                    "INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?)",
                    userId, secondPrincipal, 1L, "duplicate principal post"
            );

            mockMvc.perform(get("/v1/admin/users/" + userId)
                            .header("Authorization", auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", equalTo((int) userId)))
                    .andExpect(jsonPath("$.moderation_stats.posts_total", equalTo(1)))
                    .andExpect(jsonPath("$.ban.status", equalTo("none")));
        } finally {
            jdbc.update(
                    "DELETE FROM principals p USING principals p2 " +
                            "WHERE p.user_id = p2.user_id AND p.user_id IS NOT NULL AND p.id > p2.id"
            );
            jdbc.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1 FROM pg_constraint
                            WHERE conname = 'principals_user_id_key'
                        ) THEN
                            ALTER TABLE principals
                                ADD CONSTRAINT principals_user_id_key UNIQUE (user_id);
                        END IF;
                    END$$;
                    """);
            // Keep at least one principal row for the created user.
            Integer principalsForUser = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM principals WHERE user_id = ?",
                    Integer.class, userId
            );
            if (principalsForUser != null && principalsForUser == 0) {
                jdbc.update("INSERT INTO principals(kind, user_id) VALUES ('user', ?)", userId);
            } else if (principalsForUser != null && principalsForUser > 1) {
                jdbc.update(
                        "DELETE FROM principals p USING principals p2 " +
                                "WHERE p.user_id = p2.user_id AND p.user_id = ? AND p.id > p2.id",
                        userId
                );
            }
            // Ensure the post in this test remains valid until transaction end.
            jdbc.update(
                    "UPDATE posts SET author_principal_id = ? WHERE author_id = ? AND author_principal_id <> ?",
                    firstPrincipal, userId, firstPrincipal
            );
        }
    }
}
