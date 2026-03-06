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
class CommunityRecommendedIntegrationTest extends PostgresTestBase {

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
    void recommended_allows_onboarding_incomplete_user() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('RecOnboard', 'reconboard.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id, onboarding_step, onboarding_completed_at) VALUES (?,?,?,?,NULL)",
                "uid-rec-onboarding", "recuser", companyId, "verification");
        jdbc.update("INSERT INTO communities(kind, name, member_count) VALUES ('company','Onboarding Rec Co', 42)");

        String auth = "Bearer " + token("uid-rec-onboarding");
        mockMvc.perform(get("/v1/communities/recommended")
                        .param("kind", "company")
                        .param("limit", "1")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name", equalTo("Onboarding Rec Co")));
    }

    @Test
    void recommended_filters_by_major_kind() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('RecCo', 'rec.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-rec-1", "rhea", companyId);
        long majorId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name, member_count) VALUES ('specialization','major','Data Science', 10) RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO communities(kind, specialization_type, name, member_count) VALUES ('specialization','field','Engineering', 20)");
        jdbc.update("INSERT INTO communities(kind, name, member_count) VALUES ('company','Acme', 30)");
        long otherUserA = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-rec-a", "reca", companyId
        );
        long otherUserB = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-rec-b", "recb", companyId
        );
        jdbc.update("INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?, ?)", otherUserA, majorId);
        jdbc.update("INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?, ?)", otherUserB, majorId);

        String auth = "Bearer " + token("uid-rec-1");
        mockMvc.perform(get("/v1/communities/recommended")
                        .param("kind", "major")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void recommended_allows_unprovisioned() throws Exception {
        jdbc.update("INSERT INTO communities(kind, name, member_count) VALUES ('company','WelcomeCo', 12)");

        String auth = "Bearer " + token("uid-new-user");
        mockMvc.perform(get("/v1/communities/recommended")
                        .param("limit", "1")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].is_following", equalTo(false)));
    }

    @Test
    void recommended_supports_cursor_pagination() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('RecPageCo', 'recpage.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-rec-page-1", "pagey", companyId);

        jdbc.update("INSERT INTO communities(kind, name, member_count, created_at) VALUES ('company','TopCo', 30, '2025-01-01T00:00:00Z')");
        jdbc.update("INSERT INTO communities(kind, name, member_count, created_at) VALUES ('company','MidCo', 20, '2025-01-01T00:00:00Z')");
        jdbc.update("INSERT INTO communities(kind, name, member_count, created_at) VALUES ('company','LowCo', 10, '2025-01-01T00:00:00Z')");

        String auth = "Bearer " + token("uid-rec-page-1");

        var r1 = mockMvc.perform(get("/v1/communities/recommended")
                        .param("kind", "company")
                        .param("limit", "2")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].name", equalTo("TopCo")))
                .andExpect(jsonPath("$.items[1].name", equalTo("MidCo")))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String next = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(r1.getResponse().getContentAsString())
                .get("next_cursor").asText();

        mockMvc.perform(get("/v1/communities/recommended")
                        .param("kind", "company")
                        .param("limit", "2")
                        .param("cursor", next)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name", equalTo("LowCo")))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void recommended_personalizes_toward_user_affinity_kind() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('RecAffinity', 'rec-affinity.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-rec-affinity", "affinity", companyId);
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid = ?", Long.class, "uid-rec-affinity");

        long fieldA = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name, member_count, created_at) VALUES ('specialization','field','Affinity Field A', 100, '2025-01-01T00:00:00Z') RETURNING id",
                Long.class);
        long fieldB = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name, member_count, created_at) VALUES ('specialization','field','Affinity Field B', 100, '2025-01-01T00:00:00Z') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO communities(kind, name, member_count, created_at) VALUES ('company','Affinity Company', 100, '2025-01-01T00:00:00Z')"
        );
        jdbc.update("INSERT INTO community_follows(user_id, community_id) VALUES (?, ?)", userId, fieldA);
        jdbc.update("INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?, ?)", userId, fieldA);

        String auth = "Bearer " + token("uid-rec-affinity");
        mockMvc.perform(get("/v1/communities/recommended")
                        .param("limit", "3")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].kind", equalTo("specialization")))
                .andExpect(jsonPath("$.items[0].id", equalTo((int) fieldB)));
    }
}
