package com.looped.communities;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class PostableCommunitiesIntegrationTest extends PostgresTestBase {

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
    void lists_only_postable_communities_with_follow_metadata_and_sorting() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('PickerCo', 'picker.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-picker", "picker", companyId
        );

        long workplaceId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Picker Workplace') RETURNING id",
                Long.class
        );
        long hiddenUnverifiedId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Hidden') RETURNING id",
                Long.class
        );
        long fieldId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization', 'field', 'Engineering') RETURNING id",
                Long.class
        );

        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, expires_at) VALUES (?,?,?,?, NULL)",
                userId, workplaceId, "manual", true
        );
        jdbc.update(
                "INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?,?)",
                userId, fieldId
        );
        jdbc.update(
                "INSERT INTO community_follows(user_id, community_id, is_pinned, sort_order) VALUES (?,?,?,?)",
                userId, fieldId, true, 1
        );
        jdbc.update(
                "INSERT INTO community_follows(user_id, community_id, is_pinned, sort_order) VALUES (?,?,?,?)",
                userId, workplaceId, false, 2
        );

        String auth = "Bearer " + token("uid-picker");

        mockMvc.perform(get("/v1/me/postable-communities").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id", equalTo((int) fieldId)))
                .andExpect(jsonPath("$.items[0].canPost", equalTo(true)))
                .andExpect(jsonPath("$.items[1].id", equalTo((int) workplaceId)))
                .andExpect(jsonPath("$.items[1].canPost", equalTo(true)))
                .andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem((int) hiddenUnverifiedId))));
    }
}
