package com.looped.admin;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
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
class AdminUsersLifecycleIntegrationTest extends PostgresTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminUsersRepository admins;

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

    private String userToken(String sub, String email) {
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
    void global_user_list_defaults_to_created_desc_and_includes_account_fields() throws Exception {
        admins.insert(null, "manage@looped.com", "admin", "active",
                List.of(AdminPermissions.BAN_USER));
        String adminAuth = "Bearer " + token("admin-manage", "manage@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ListCo', 'list.co') RETURNING id",
                Long.class
        );
        long u1 = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, email, company_id) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-list-1", "userone", "one@list.co", companyId
        );
        long u2 = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, email, company_id) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-list-2", "usertwo", "two@list.co", companyId
        );
        jdbc.update("UPDATE users SET created_at = now() - interval '2 days' WHERE id = ?", u1);
        jdbc.update("UPDATE users SET created_at = now() - interval '1 day' WHERE id = ?", u2);

        mockMvc.perform(get("/v1/admin/users")
                        .header("Authorization", adminAuth)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id", equalTo((int) u2)))
                .andExpect(jsonPath("$.items[0].account_status", equalTo("active")))
                .andExpect(jsonPath("$.items[0].disabled_at", nullValue()))
                .andExpect(jsonPath("$.items[0].disabled_reason", nullValue()))
                .andExpect(jsonPath("$.items[0].deleted_at", nullValue()))
                .andExpect(jsonPath("$.items[0].ban").exists());
    }

    @Test
    void global_user_list_can_filter_banned_only() throws Exception {
        admins.insert(null, "manage-ban@looped.com", "admin", "active",
                List.of(AdminPermissions.BAN_USER));
        String adminAuth = "Bearer " + token("admin-manage-ban", "manage-ban@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('BanCo', 'ban.co') RETURNING id",
                Long.class
        );
        long bannedUser = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, email, company_id) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-banned", "banneduser", "b@ban.co", companyId
        );
        jdbc.queryForObject(
                "INSERT INTO user_bans(user_id, reason) VALUES (?, 'spam') RETURNING id",
                Long.class, bannedUser
        );
        jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, email, company_id) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-notbanned", "notbanned", "n@ban.co", companyId
        );

        mockMvc.perform(get("/v1/admin/users")
                        .header("Authorization", adminAuth)
                        .param("banned", "true")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", equalTo(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo((int) bannedUser)))
                .andExpect(jsonPath("$.items[0].ban.status", equalTo("banned")));
    }

    @Test
    void disable_then_enable_blocks_and_restores_user_access() throws Exception {
        admins.insert(null, "manage2@looped.com", "admin", "active",
                List.of(AdminPermissions.BAN_USER));
        String adminAuth = "Bearer " + token("admin-manage-2", "manage2@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('DisableCo', 'disable.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, email, company_id) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-disabled", "disableduser", "d@disable.co", companyId
        );

        mockMvc.perform(post("/v1/admin/users/" + userId + "/disable")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"fraud\",\"notify_user\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) userId)))
                .andExpect(jsonPath("$.account_status", equalTo("disabled")))
                .andExpect(jsonPath("$.disabled_reason", equalTo("fraud")));

        String userAuth = "Bearer " + userToken("uid-disabled", "d@disable.co");
        mockMvc.perform(get("/v1/me").header("Authorization", userAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("account_disabled")));

        mockMvc.perform(post("/v1/admin/users/" + userId + "/enable")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"appeal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account_status", equalTo("active")))
                .andExpect(jsonPath("$.disabled_at", nullValue()))
                .andExpect(jsonPath("$.disabled_reason", nullValue()));

        mockMvc.perform(get("/v1/me").header("Authorization", userAuth))
                .andExpect(status().isOk());
    }

    @Test
    void admin_soft_delete_blocks_user_access() throws Exception {
        admins.insert(null, "manage3@looped.com", "admin", "active",
                List.of(AdminPermissions.BAN_USER));
        String adminAuth = "Bearer " + token("admin-manage-3", "manage3@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('DeleteCo', 'delete.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, email, company_id) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-deleted", "deleteduser", "del@delete.co", companyId
        );

        mockMvc.perform(post("/v1/admin/users/" + userId + "/delete")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"gdpr\",\"mode\":\"soft\",\"confirm\":\"DELETE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) userId)))
                .andExpect(jsonPath("$.account_status", equalTo("deleted")))
                .andExpect(jsonPath("$.deleted_at").exists());

        String userAuth = "Bearer " + userToken("uid-deleted", "del@delete.co");
        mockMvc.perform(get("/v1/me").header("Authorization", userAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("account_deleted")));
    }

    @Test
    void cannot_delete_admin_account() throws Exception {
        admins.insert(null, "manage4@looped.com", "admin", "active",
                List.of(AdminPermissions.BAN_USER));
        String adminAuth = "Bearer " + token("admin-manage-4", "manage4@looped.com");

        admins.insert("uid-admin-target", "target@looped.com", "admin", "active",
                List.of(AdminPermissions.BAN_USER));

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ProtectCo', 'protect.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, email, company_id) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-admin-target", "admintarget", "t@protect.co", companyId
        );

        mockMvc.perform(post("/v1/admin/users/" + userId + "/delete")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test\",\"mode\":\"soft\",\"confirm\":\"DELETE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", equalTo("cannot_delete_admin")));
    }
}
