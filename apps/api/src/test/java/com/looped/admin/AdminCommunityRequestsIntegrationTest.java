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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class AdminCommunityRequestsIntegrationTest extends PostgresTestBase {

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
    void approve_request_creates_community() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme', 'acme.com') RETURNING id",
                Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-requester", "requester", companyId);
        long requestId = jdbc.queryForObject(
                "INSERT INTO community_requests(user_id, kind, name, description) VALUES (?,?,?,?) RETURNING id",
                Long.class, userId, "company", "Design", "For designers");

        admins.insert(null, "admin@looped.com", "owner", "active",
                List.of(AdminPermissions.CREATE_COMMUNITY));
        String auth = "Bearer " + token("admin-uid", "admin@looped.com");

        mockMvc.perform(post("/v1/admin/community-requests/" + requestId + "/approve")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("approved")))
                .andExpect(jsonPath("$.community_id").isNumber());

        Integer communities = jdbc.queryForObject(
                "SELECT COUNT(*) FROM communities WHERE kind = 'company' AND name = 'Design'",
                Integer.class
        );
        String status = jdbc.queryForObject(
                "SELECT status FROM community_requests WHERE id = ?",
                String.class, requestId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, communities.intValue());
        org.junit.jupiter.api.Assertions.assertEquals("approved", status);
    }
}
