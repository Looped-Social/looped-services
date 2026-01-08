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
    void recommended_filters_by_major_kind() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('RecCo', 'rec.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-rec-1", "rhea", companyId);
        jdbc.update("INSERT INTO communities(kind, specialization_type, name, member_count) VALUES ('specialization','major','Data Science', 10)");
        jdbc.update("INSERT INTO communities(kind, specialization_type, name, member_count) VALUES ('specialization','department','Engineering', 20)");
        jdbc.update("INSERT INTO communities(kind, name, member_count) VALUES ('company','Acme', 30)");

        String auth = "Bearer " + token("uid-rec-1");
        mockMvc.perform(get("/v1/communities/recommended")
                        .param("kind", "major")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].kind", equalTo("specialization")))
                .andExpect(jsonPath("$.items[0].specialization_type", equalTo("major")))
                .andExpect(jsonPath("$.items[0].name", equalTo("Data Science")));
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
}
