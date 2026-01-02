package com.looped.integration;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class CommunityPermissionsIntegrationTest extends PostgresTestBase {

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
    void permissions_reflect_verification_status() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('PermCo','perm.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-perm-user", "permuser", companyId
        );
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'PermCo') RETURNING id", Long.class);

        String auth = "Bearer " + token("uid-perm-user");

        mockMvc.perform(get("/v1/communities/" + communityId + "/permissions")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.can_post").value(false))
                .andExpect(jsonPath("$.requires_verification").value(true));

        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                userId, communityId, "manual", true);

        mockMvc.perform(get("/v1/communities/" + communityId + "/permissions")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.can_post").value(true))
                .andExpect(jsonPath("$.requires_verification").value(true));
    }

    @Test
    void specialization_community_allows_posting_without_verification() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('SpecCo','spec.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-spec-user", "specuser", companyId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name, specialization_type) VALUES ('specialization', 'SpecCo', 'role') RETURNING id", Long.class);

        String auth = "Bearer " + token("uid-spec-user");

        mockMvc.perform(get("/v1/communities/" + communityId + "/permissions")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.can_post").value(true))
                .andExpect(jsonPath("$.requires_verification").value(false));
    }
}
