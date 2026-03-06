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
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class SpecializationsBrowseIntegrationTest extends PostgresTestBase {

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
    void browse_allows_onboarding_incomplete_user() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('BrowseOnboard', 'browseonboard.co') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id, onboarding_step, onboarding_completed_at) VALUES (?,?,?,?,NULL)",
                "uid-browse-onboarding", "browseonboard", companyId, "verification");
        jdbc.update("INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','field','Biomedical')");

        String auth = "Bearer " + token("uid-browse-onboarding");
        mockMvc.perform(get("/v1/specializations/browse")
                        .param("type", "field")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].specialization_type", equalTo("field")));
    }

    @Test
    void browse_majors_is_cursor_paginated_and_ordered() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('BrowseCo', 'browse.co') RETURNING id",
                Long.class);
        long actorUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-browse-1", "brooke", companyId);

        long tieAId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name, created_at) " +
                        "VALUES ('specialization','major','Tie A','2025-01-02T00:00:00Z') RETURNING id",
                Long.class);
        long tieBId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name, created_at) " +
                        "VALUES ('specialization','major','Tie B','2025-01-02T00:00:00Z') RETURNING id",
                Long.class);
        long popularId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name, created_at) " +
                        "VALUES ('specialization','major','Popular Major','2025-01-02T00:00:00Z') RETURNING id",
                Long.class);

        jdbc.update("INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?,?)", actorUserId, popularId);
        jdbc.update("INSERT INTO community_follows(user_id, community_id) VALUES (?,?)", actorUserId, tieAId);

        for (int i = 0; i < 3; i++) {
            long userId = jdbc.queryForObject(
                    "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                    Long.class,
                    "uid-browse-pop-" + i,
                    "pop" + i,
                    companyId
            );
            jdbc.update("INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?,?)", userId, popularId);
        }
        for (int i = 0; i < 2; i++) {
            long userId = jdbc.queryForObject(
                    "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                    Long.class,
                    "uid-browse-tie-a-" + i,
                    "tiea" + i,
                    companyId
            );
            jdbc.update("INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?,?)", userId, tieAId);
        }
        for (int i = 0; i < 2; i++) {
            long userId = jdbc.queryForObject(
                    "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                    Long.class,
                    "uid-browse-tie-b-" + i,
                    "tieb" + i,
                    companyId
            );
            jdbc.update("INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?,?)", userId, tieBId);
        }

        String auth = "Bearer " + token("uid-browse-1");

        mockMvc.perform(get("/v1/specializations/browse")
                        .param("type", "major")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void browse_rejects_invalid_type() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('BrowseTypeCo', 'browsetype.co') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-browse-2", "bri", companyId);

        String auth = "Bearer " + token("uid-browse-2");

        mockMvc.perform(get("/v1/specializations/browse")
                        .param("type", "department")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", equalTo("invalid_specialization_type")));
    }
}
