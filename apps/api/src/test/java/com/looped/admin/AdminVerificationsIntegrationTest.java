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
}

