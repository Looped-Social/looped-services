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
class CommunitySearchIntegrationTest extends PostgresTestBase {

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
    void search_allows_onboarding_incomplete_user() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('SearchOnboard', 'searchonboard.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id, onboarding_step, onboarding_completed_at) VALUES (?,?,?,?,NULL)",
                "uid-search-onboarding", "onboarduser", companyId, "verification");
        jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company','Onboarding Company') RETURNING id",
                Long.class);

        String auth = "Bearer " + token("uid-search-onboarding");
        mockMvc.perform(get("/v1/communities/search")
                        .param("query", "onboarding")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name", equalTo("Onboarding Company")));
    }

    @Test
    void search_filters_by_major() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('SearchCo', 'search.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-search-1", "sara", companyId);
        jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','Data Science') RETURNING id",
                Long.class);
        jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','field','Data Engineering') RETURNING id",
                Long.class);
        jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company','RetailCo') RETURNING id",
                Long.class);

        String auth = "Bearer " + token("uid-search-1");
        mockMvc.perform(get("/v1/communities/search")
                        .param("query", "data")
                        .param("kind", "major")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].kind", equalTo("specialization")))
                .andExpect(jsonPath("$.items[0].specialization_type", equalTo("major")))
                .andExpect(jsonPath("$.items[0].name", equalTo("Data Science")));
    }

    @Test
    void search_filters_by_field() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('SearchTwo', 'searchtwo.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-search-2", "nori", companyId);
        jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','Data Science') RETURNING id",
                Long.class);
        jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','field','Data Engineering') RETURNING id",
                Long.class);

        String auth = "Bearer " + token("uid-search-2");
        mockMvc.perform(get("/v1/communities/search")
                        .param("query", "data")
                        .param("kind", "field")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].kind", equalTo("specialization")))
                .andExpect(jsonPath("$.items[0].specialization_type", equalTo("field")))
                .andExpect(jsonPath("$.items[0].name", equalTo("Data Engineering")));
    }
}
