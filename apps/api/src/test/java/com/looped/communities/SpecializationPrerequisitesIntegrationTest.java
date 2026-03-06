package com.looped.communities;

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
class SpecializationPrerequisitesIntegrationTest extends PostgresTestBase {

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
    void joining_major_is_not_supported() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('MajCo','maj.co') RETURNING id", Long.class);
        jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-major-prereq", "majorprereq", companyId
        );
        long majorId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','Economics') RETURNING id",
                Long.class
        );

        String auth = "Bearer " + token("uid-major-prereq");
        mockMvc.perform(post("/v1/specializations/" + majorId + "/join").header("Authorization", auth))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", equalTo("invalid_specialization")));
    }

    @Test
    void joining_field_requires_verified_company() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('FldCo','fld.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-field-prereq", "fieldprereq", companyId
        );
        long fieldId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','field','Engineering') RETURNING id",
                Long.class
        );

        String auth = "Bearer " + token("uid-field-prereq");
        mockMvc.perform(post("/v1/specializations/" + fieldId + "/join").header("Authorization", auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("specialization_verification_required")))
                .andExpect(jsonPath("$.specialization_type", equalTo("field")))
                .andExpect(jsonPath("$.required_verification_kind", equalTo("company")));

        long companyCommunityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'FldCo') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, expires_at) VALUES (?,?,?,?, NULL)",
                userId, companyCommunityId, "manual", true
        );

        mockMvc.perform(post("/v1/specializations/" + fieldId + "/join").header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.joined").value(true));
    }

    @Test
    void completed_onboarding_skip_path_does_not_block_join_when_required_verification_exists() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('JoinFixCo','joinfix.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-field-join-fix", "fieldjoinfix", companyId
        );
        long fieldId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','field','Join Fix Field') RETURNING id",
                Long.class
        );
        long verifiedCompanyCommunityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Join Fix Company Community') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) VALUES (?,?,?,?,now(),NULL)",
                userId, verifiedCompanyCommunityId, "manual", true
        );
        jdbc.update(
                "INSERT INTO user_onboarding_v2(user_id, stage_v2, selected_org_kind, verification_path, verification_status, requires_specialization_selection, updated_at) " +
                        "VALUES (?,?,?,?,?,?,now())",
                userId, "completed", "company", "skip", "none", false
        );

        String auth = "Bearer " + token("uid-field-join-fix");
        mockMvc.perform(get("/v1/me/specializations/join-limits?type=field").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].specialization_type", equalTo("field")))
                .andExpect(jsonPath("$.items[0].required_verification_kind", equalTo("company")))
                .andExpect(jsonPath("$.items[0].can_join", equalTo(true)));

        mockMvc.perform(post("/v1/specializations/" + fieldId + "/join").header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.joined").value(true));
    }
}
