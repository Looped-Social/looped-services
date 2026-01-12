package com.looped.communities;

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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class CommunityFollowsIntegrationTest extends PostgresTestBase {

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
    void list_followed_communities() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme', 'acme.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-communities", "lori", companyId);

        long commA = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'Acme') RETURNING id", Long.class);
        long commB = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('sector', 'Finance') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid=?", Long.class, "uid-communities");
        jdbc.update("INSERT INTO community_follows(user_id, community_id, is_pinned, sort_order) VALUES (?,?,?,?)",
                userId, commA, true, 1);
        jdbc.update("INSERT INTO community_follows(user_id, community_id, is_pinned, sort_order) VALUES (?,?,?,?)",
                userId, commB, false, null);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified) VALUES (?,?,?,?)",
                userId, commA, "manual", true);

        String auth = "Bearer " + token("uid-communities");

        mockMvc.perform(get("/v1/me/followed/communities")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].name", containsInAnyOrder("Acme", "Finance")))
                .andExpect(jsonPath("$.items[*].member_count", containsInAnyOrder(1, 0)))
                .andExpect(jsonPath("$.items[*].can_post", containsInAnyOrder(true, false)));
    }

    @Test
    void follow_and_unfollow_community() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Gamma', 'gamma.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-communities-2", "miles", companyId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('sector', 'HR') RETURNING id", Long.class);

        String auth = "Bearer " + token("uid-communities-2");

        mockMvc.perform(get("/v1/me/followed/communities")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        mockMvc.perform(post("/v1/communities/" + communityId + "/follow")
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.following").value(true));

        mockMvc.perform(get("/v1/me/followed/communities")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));

        mockMvc.perform(delete("/v1/communities/" + communityId + "/follow")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(false));
    }

    @Test
    void follow_specialization_does_not_enforce_join_rules() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Delta', 'delta.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-specialization-1", "mara", companyId);
        long majorOne = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','Data Science') RETURNING id",
                Long.class);
        long majorTwo = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','Statistics') RETURNING id",
                Long.class);
        long majorThree = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','Mathematics') RETURNING id",
                Long.class);

        String auth = "Bearer " + token("uid-specialization-1");

        mockMvc.perform(post("/v1/communities/" + majorOne + "/follow")
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.following").value(true));

        mockMvc.perform(post("/v1/communities/" + majorTwo + "/follow")
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.following").value(true));

        mockMvc.perform(post("/v1/communities/" + majorThree + "/follow")
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.following").value(true));
    }

    @Test
    void join_specialization_enforces_limit_and_cooldown() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Echo', 'echo.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-specialization-2", "nina", companyId);
        long majorOne = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','Economics') RETURNING id",
                Long.class);
        long majorTwo = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','Finance') RETURNING id",
                Long.class);
        long majorThree = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','Accounting') RETURNING id",
                Long.class);

        String auth = "Bearer " + token("uid-specialization-2");

        mockMvc.perform(post("/v1/specializations/" + majorOne + "/join")
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.joined").value(true));

        mockMvc.perform(post("/v1/specializations/" + majorTwo + "/join")
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.joined").value(true));

        mockMvc.perform(post("/v1/specializations/" + majorThree + "/join")
                        .header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", equalTo("specialization_join_limit")))
                .andExpect(jsonPath("$.specialization_type", equalTo("major")))
                .andExpect(jsonPath("$.limit", equalTo(2)));

        mockMvc.perform(delete("/v1/specializations/" + majorOne + "/join")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joined").value(false));

        mockMvc.perform(post("/v1/specializations/" + majorThree + "/join")
                        .header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", equalTo("specialization_join_cooldown")))
                .andExpect(jsonPath("$.specialization_type", equalTo("major")));
    }
}
