package com.looped.admin;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
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
class AdminVerificationsIntegrationTest extends PostgresTestBase {
    private static final ObjectMapper JSON = new ObjectMapper();

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
    void admin_verification_list_and_detail_include_community_fields_even_when_null() throws Exception {
        admins.insert(null, "verify-admin@looped.com", "admin", "active",
                List.of(AdminPermissions.VERIFY_USERS));
        String auth = "Bearer " + token("admin-verify", "verify-admin@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('UNC Co', 'unc.edu') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-verif-user", "verifuser", companyId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('school', 'UNC Chapel Hill') RETURNING id",
                Long.class
        );

        long nonCommunityReqId = jdbc.queryForObject(
                "INSERT INTO verification_requests(user_id, community_id, email, method, status, submitted_at) " +
                        "VALUES (?, NULL, ?, 'email', 'pending', now() - interval '1 day') RETURNING id",
                Long.class, userId, "user@unc.edu"
        );
        long communityReqId = jdbc.queryForObject(
                "INSERT INTO verification_requests(user_id, community_id, email, method, status, submitted_at) " +
                        "VALUES (?, ?, ?, 'photo_id', 'pending', now() - interval '2 days') RETURNING id",
                Long.class, userId, communityId, "user@unc.edu"
        );

        mockMvc.perform(get("/v1/admin/verifications")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value((int) nonCommunityReqId))
                .andExpect(jsonPath("$.items[0].community_id").value(nullValue()))
                .andExpect(jsonPath("$.items[0].community_name").value(nullValue()))
                .andExpect(jsonPath("$.items[1].id").value((int) communityReqId))
                .andExpect(jsonPath("$.items[1].community_id").value((int) communityId))
                .andExpect(jsonPath("$.items[1].community_name").value("UNC Chapel Hill"));

        mockMvc.perform(get("/v1/admin/verifications/" + nonCommunityReqId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) nonCommunityReqId))
                .andExpect(jsonPath("$.community_id").value(nullValue()))
                .andExpect(jsonPath("$.community_name").value(nullValue()));

        mockMvc.perform(get("/v1/admin/verifications/" + communityReqId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) communityReqId))
                .andExpect(jsonPath("$.community_id").value((int) communityId))
                .andExpect(jsonPath("$.community_name").value(equalTo("UNC Chapel Hill")));
    }

    @Test
    void pending_defaults_to_oldest_first_and_paginates_stably() throws Exception {
        admins.insert(null, "verify-admin2@looped.com", "admin2", "active",
                List.of(AdminPermissions.VERIFY_USERS));
        String auth = "Bearer " + token("admin-verify-2", "verify-admin2@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme Co', 'acme.com') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, display_name, company_id) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-verif-user2", "verif_user2", "Verif User2", companyId
        );

        long reqA = jdbc.queryForObject(
                "INSERT INTO verification_requests(user_id, email, method, status, submitted_at) " +
                        "VALUES (?, 'user2@acme.com', 'photo_id', 'pending', TIMESTAMPTZ '2026-01-01T00:00:00Z') RETURNING id",
                Long.class, userId
        );
        long reqB = jdbc.queryForObject(
                "INSERT INTO verification_requests(user_id, email, method, status, submitted_at) " +
                        "VALUES (?, 'user2@acme.com', 'photo_id', 'pending', TIMESTAMPTZ '2026-01-01T00:00:00Z') RETURNING id",
                Long.class, userId
        );
        long reqC = jdbc.queryForObject(
                "INSERT INTO verification_requests(user_id, email, method, status, submitted_at) " +
                        "VALUES (?, 'user2@acme.com', 'photo_id', 'pending', TIMESTAMPTZ '2026-01-02T00:00:00Z') RETURNING id",
                Long.class, userId
        );

        var page1 = mockMvc.perform(get("/v1/admin/verifications")
                        .param("status", "pending")
                        .param("limit", "2")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value((int) reqA))
                .andExpect(jsonPath("$.items[1].id").value((int) reqB))
                .andExpect(jsonPath("$.next_cursor").exists())
                .andReturn();

        String next = JSON.readTree(page1.getResponse().getContentAsString()).path("next_cursor").asText();

        mockMvc.perform(get("/v1/admin/verifications")
                        .param("status", "pending")
                        .param("limit", "2")
                        .param("cursor", next)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value((int) reqC))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void pending_can_be_sorted_newest_first_when_requested() throws Exception {
        admins.insert(null, "verify-admin3@looped.com", "admin3", "active",
                List.of(AdminPermissions.VERIFY_USERS));
        String auth = "Bearer " + token("admin-verify-3", "verify-admin3@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Beta Co', 'beta.com') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, display_name, company_id) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-verif-user3", "verif_user3", "Verif User3", companyId
        );

        long older = jdbc.queryForObject(
                "INSERT INTO verification_requests(user_id, email, method, status, submitted_at) " +
                        "VALUES (?, 'user3@beta.com', 'photo_id', 'pending', TIMESTAMPTZ '2026-01-01T00:00:00Z') RETURNING id",
                Long.class, userId
        );
        long newer = jdbc.queryForObject(
                "INSERT INTO verification_requests(user_id, email, method, status, submitted_at) " +
                        "VALUES (?, 'user3@beta.com', 'photo_id', 'pending', TIMESTAMPTZ '2026-01-03T00:00:00Z') RETURNING id",
                Long.class, userId
        );

        mockMvc.perform(get("/v1/admin/verifications")
                        .param("status", "pending")
                        .param("sort", "newest")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value((int) newer))
                .andExpect(jsonPath("$.items[1].id").value((int) older));
    }

    @Test
    void q_search_matches_handle_display_name_and_email() throws Exception {
        admins.insert(null, "verify-admin4@looped.com", "admin4", "active",
                List.of(AdminPermissions.VERIFY_USERS));
        String auth = "Bearer " + token("admin-verify-4", "verify-admin4@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Gamma Co', 'gamma.com') RETURNING id",
                Long.class
        );
        long sarahId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, display_name, email, company_id) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, "uid-sarah", "sarah_w", "Sarah W", "sarah@gamma.com", companyId
        );
        long jonId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, display_name, email, company_id) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, "uid-jon", "jonny", "Jon Smith", "jon.smith@gamma.com", companyId
        );

        long sarahReq = jdbc.queryForObject(
                "INSERT INTO verification_requests(user_id, email, method, status, submitted_at) " +
                        "VALUES (?, 'sarah.verify@gamma.com', 'photo_id', 'pending', TIMESTAMPTZ '2026-01-01T00:00:00Z') RETURNING id",
                Long.class, sarahId
        );
        long jonReq = jdbc.queryForObject(
                "INSERT INTO verification_requests(user_id, email, method, status, submitted_at) " +
                        "VALUES (?, 'jon.verify@gamma.com', 'photo_id', 'pending', TIMESTAMPTZ '2026-01-02T00:00:00Z') RETURNING id",
                Long.class, jonId
        );

        mockMvc.perform(get("/v1/admin/verifications")
                        .param("status", "pending")
                        .param("q", "sarahw")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value((int) sarahReq));

        mockMvc.perform(get("/v1/admin/verifications")
                        .param("status", "pending")
                        .param("q", "jon smi")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value((int) jonReq));

        mockMvc.perform(get("/v1/admin/verifications")
                        .param("status", "pending")
                        .param("q", "verify@gamma")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value((int) sarahReq))
                .andExpect(jsonPath("$.items[1].id").value((int) jonReq));
    }
}
