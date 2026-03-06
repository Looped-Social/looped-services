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
class AdminUserAccessIntegrationTest extends PostgresTestBase {

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

    private String userToken(String sub) {
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
    void revoke_community_verification_marks_unverified() throws Exception {
        admins.insert(null, "verify@looped.com", "admin", "active",
                List.of(AdminPermissions.VERIFY_USERS));
        String adminAuth = "Bearer " + token("admin-verify", "verify@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('RevokeCo', 'revoke.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-revoke", "revokeuser", companyId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('school', 'Revoke U') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) " +
                        "VALUES (?,?,?,?, now(), now() + interval '10 days')",
                userId, communityId, "manual", true
        );

        mockMvc.perform(post("/v1/admin/users/" + userId + "/community-verifications/" + communityId + "/revoke")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("revoked")))
                .andExpect(jsonPath("$.user_id", equalTo((int) userId)))
                .andExpect(jsonPath("$.community_id", equalTo((int) communityId)));

        Boolean verified = jdbc.queryForObject(
                "SELECT verified FROM community_verifications WHERE user_id=? AND community_id=?",
                Boolean.class, userId, communityId
        );
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE, verified);
        Integer verifiedAtCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_verifications WHERE user_id=? AND community_id=? AND verified_at IS NOT NULL",
                Integer.class, userId, communityId
        );
        Integer expiresAtCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_verifications WHERE user_id=? AND community_id=? AND expires_at IS NOT NULL",
                Integer.class, userId, communityId
        );
        org.junit.jupiter.api.Assertions.assertEquals(0, verifiedAtCount.intValue());
        org.junit.jupiter.api.Assertions.assertEquals(0, expiresAtCount.intValue());
    }

    @Test
    void reset_specialization_join_limits_can_clear_cooldown_and_joins() throws Exception {
        admins.insert(null, "verify2@looped.com", "admin", "active",
                List.of(AdminPermissions.VERIFY_USERS));
        String adminAuth = "Bearer " + token("admin-verify-2", "verify2@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ResetCo', 'reset.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-reset", "resetuser", companyId
        );
        long fieldId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','field','Biology') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?,?)", userId, fieldId);
        jdbc.update(
                "INSERT INTO user_specialization_limits(user_id, specialization_type, scope, last_changed_at) VALUES (?,?,?, now())",
                userId, "field", "join"
        );

        mockMvc.perform(post("/v1/admin/users/" + userId + "/specializations/join-limits/reset")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"specialization_type\":\"field\",\"clear_joins\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("reset")))
                .andExpect(jsonPath("$.cooldowns_cleared", equalTo(1)))
                .andExpect(jsonPath("$.joins_removed", equalTo(1)));

        Integer cooldownRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_specialization_limits WHERE user_id=? AND specialization_type='field' AND scope='join'",
                Integer.class, userId
        );
        Integer joinRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM specialization_joins WHERE user_id=?",
                Integer.class, userId
        );
        org.junit.jupiter.api.Assertions.assertEquals(0, cooldownRows.intValue());
        org.junit.jupiter.api.Assertions.assertEquals(0, joinRows.intValue());

        long schoolId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('school', 'Reset U') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, expires_at) VALUES (?,?,?,?, NULL)",
                userId, schoolId, "manual", true
        );

        String userAuth = "Bearer " + userToken("uid-reset");
        mockMvc.perform(get("/v1/me/specializations/join-limits?type=field")
                        .header("Authorization", userAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].specialization_type").value("field"))
                .andExpect(jsonPath("$.items[0].joined_count").value(0))
                .andExpect(jsonPath("$.items[0].cooldown_active").value(false))
                .andExpect(jsonPath("$.items[0].can_join").value(true));
    }
}
