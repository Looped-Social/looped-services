package com.looped.discovery;

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
class SpecializationsRecommendedIntegrationTest extends PostgresTestBase {

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
    void recommended_specializations_allows_onboarding_incomplete_user() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('SpecOnboard', 'speconboard.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id, onboarding_step, onboarding_completed_at) VALUES (?,?,?,?,NULL)",
                "uid-spec-onboarding", "speconboard", companyId, "verification");
        jdbc.update("INSERT INTO communities(kind, specialization_type, name, member_count) VALUES ('specialization','major','Design', 8)");

        String auth = "Bearer " + token("uid-spec-onboarding");
        mockMvc.perform(get("/v1/specializations/recommended")
                        .param("type", "major")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void recommended_specializations_returns_both_types() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('SpecCo', 'spec.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-spec-1", "specuser", companyId);
        jdbc.update("INSERT INTO communities(kind, specialization_type, name, member_count) VALUES ('specialization','major','Data Science', 10)");
        jdbc.update("INSERT INTO communities(kind, specialization_type, name, member_count) VALUES ('specialization','field','Engineering', 20)");
        jdbc.update("INSERT INTO communities(kind, name, member_count) VALUES ('company','Acme', 30)");

        String auth = "Bearer " + token("uid-spec-1");
        mockMvc.perform(get("/v1/specializations/recommended")
                        .param("type", "all")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.majors", hasSize(0)))
                .andExpect(jsonPath("$.fields", hasSize(1)))
                .andExpect(jsonPath("$.fields[0].kind", equalTo("specialization")))
                .andExpect(jsonPath("$.fields[0].specialization_type", equalTo("field")));
    }

    @Test
    void recommended_specializations_rejects_invalid_type() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('SpecCoTwo', 'spectwo.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-spec-2", "specuser2", companyId);

        String auth = "Bearer " + token("uid-spec-2");
        mockMvc.perform(get("/v1/specializations/recommended")
                        .param("type", "nope")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", equalTo("invalid_specialization_type")));
    }
}
