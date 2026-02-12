package com.looped.anon;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class AnonIssuePermissionsIntegrationTest extends PostgresTestBase {

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
    void issue_requires_verification_for_non_specialization_community() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('AnonIssueCo','anon-issue.co') RETURNING id", Long.class);
        jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-issue-verify", "anonissueverify", companyId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'AnonIssueCo') RETURNING id", Long.class);

        mockMvc.perform(post("/anon/issue")
                        .header("Authorization", "Bearer " + token("uid-anon-issue-verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communityId\":" + communityId + ",\"blindedMessage\":\"AA==\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("community_not_verified"));
    }

    @Test
    void issue_requires_specialization_join_for_field_or_major() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('AnonSpecCo','anon-spec.co') RETURNING id", Long.class);
        jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-issue-join", "anonissuejoin", companyId);
        long specializationId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','field','AnonSpecField') RETURNING id",
                Long.class
        );

        mockMvc.perform(post("/anon/issue")
                        .header("Authorization", "Bearer " + token("uid-anon-issue-join"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communityId\":" + specializationId + ",\"blindedMessage\":\"AA==\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("specialization_not_joined"));
    }
}
