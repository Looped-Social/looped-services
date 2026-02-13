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
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
class AdminAnnouncementsIntegrationTest extends PostgresTestBase {

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

    @Test
    void global_announcement_sends_to_all_active_users() throws Exception {
        admins.insert(null, "owner-announcements@looped.com", "owner", "active",
                List.of(AdminPermissions.SEND_GLOBAL_ANNOUNCEMENTS));
        String auth = "Bearer " + token("owner-announcements", "owner-announcements@looped.com");

        long companyA = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme A','acmea.co') RETURNING id",
                Long.class
        );
        long companyB = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme B','acmeb.co') RETURNING id",
                Long.class
        );

        long userA = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-global-a", "globala", companyA
        );
        long userB = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-global-b", "globalb", companyB
        );
        long deletedUser = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, deleted_at) VALUES (?,?,?, now()) RETURNING id",
                Long.class, "uid-global-deleted", "globaldeleted", companyB
        );

        mockMvc.perform(post("/v1/admin/announcements/global")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Platform update",
                                  "body": "We shipped stability improvements.",
                                  "deeplink": "looped://updates"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sent", equalTo(2)));

        assertEquals(
                2,
                jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'announcement'", Integer.class)
        );
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM notifications WHERE type = 'announcement' " +
                                "AND payload->>'title' = ? " +
                                "AND payload->>'body' = ? " +
                                "AND payload->>'action_deeplink' = ? " +
                                "AND payload->>'deeplink' = ?",
                        Integer.class,
                        "Platform update",
                        "We shipped stability improvements.",
                        "looped://updates",
                        "looped://updates"
                )
        );
        assertEquals(
                0,
                jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Integer.class, deletedUser)
        );

        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Integer.class, userA)
        );
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Integer.class, userB)
        );
    }

    @Test
    void global_announcement_requires_global_permission() throws Exception {
        admins.insert(null, "admin-announcements@looped.com", "admin", "active",
                List.of(AdminPermissions.SEND_ANNOUNCEMENTS));
        String auth = "Bearer " + token("admin-announcements", "admin-announcements@looped.com");

        mockMvc.perform(post("/v1/admin/announcements/global")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Platform update",
                                  "body": "This should be blocked."
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("forbidden")));
    }

    @Test
    void list_announcements_supports_scope_and_company_filters_newest_first() throws Exception {
        admins.insert(null, "admin-list@looped.com", "owner", "active",
                List.of(AdminPermissions.SEND_ANNOUNCEMENTS, AdminPermissions.SEND_GLOBAL_ANNOUNCEMENTS));
        String auth = "Bearer " + token("admin-list", "admin-list@looped.com");

        long companyA = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Alpha Co','alpha.co') RETURNING id",
                Long.class
        );
        long companyB = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Beta Co','beta.co') RETURNING id",
                Long.class
        );
        jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-list-a", "lista", companyA
        );
        jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-list-b", "listb", companyB
        );

        mockMvc.perform(post("/v1/admin/announcements")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyId": %d,
                                  "title": "Company Alpha",
                                  "body": "Alpha body"
                                }
                                """.formatted(companyA)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/admin/announcements/global")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Global announcement",
                                  "body": "Global body"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/admin/announcements")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyId": %d,
                                  "title": "Company Beta",
                                  "body": "Beta body"
                                }
                                """.formatted(companyB)))
                .andExpect(status().isCreated());

        var page1 = mockMvc.perform(get("/v1/admin/announcements?limit=2")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].title", equalTo("Company Beta")))
                .andExpect(jsonPath("$.items[0].scope", equalTo("company")))
                .andExpect(jsonPath("$.items[1].title", equalTo("Global announcement")))
                .andExpect(jsonPath("$.items[1].scope", equalTo("global")))
                .andReturn();

        String page1Json = page1.getResponse().getContentAsString();
        String cursor = com.jayway.jsonpath.JsonPath.read(page1Json, "$.next_cursor");

        mockMvc.perform(get("/v1/admin/announcements?cursor=" + cursor + "&limit=2")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].title", equalTo("Company Alpha")))
                .andExpect(jsonPath("$.items[0].scope", equalTo("company")));

        mockMvc.perform(get("/v1/admin/announcements?scope=global")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].title", equalTo("Global announcement")))
                .andExpect(jsonPath("$.items[0].scope", equalTo("global")));

        mockMvc.perform(get("/v1/admin/announcements?scope=company&companyId=" + companyA)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].title", equalTo("Company Alpha")))
                .andExpect(jsonPath("$.items[0].scope", equalTo("company")))
                .andExpect(jsonPath("$.items[0].company_id", equalTo((int) companyA)));
    }
}
