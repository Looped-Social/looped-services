package com.looped.integration;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class DisplaySpecializationIntegrationTest extends PostgresTestBase {

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
    void set_display_specialization_allows_major_or_department() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('SpecCo','spec.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-display-spec", "displayspec", companyId);
        long specializationId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization', 'major', 'Data Science') RETURNING id",
                Long.class
        );

        String auth = "Bearer " + token("uid-display-spec");

        mockMvc.perform(put("/v1/users/me/display-specialization")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"specializationId\":" + specializationId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_specialization.id").value((int) specializationId))
                .andExpect(jsonPath("$.display_specialization.specialization_type").value("major"));
    }

    @Test
    void set_display_specialization_rejects_invalid_kind() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('SpecInvalid','spec.invalid') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-display-spec-bad", "displayspecbad", companyId);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('school', 'SpecSchool') RETURNING id",
                Long.class
        );

        String auth = "Bearer " + token("uid-display-spec-bad");

        mockMvc.perform(put("/v1/users/me/display-specialization")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"specializationId\":" + communityId + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(equalTo("invalid_specialization")));
    }
}
