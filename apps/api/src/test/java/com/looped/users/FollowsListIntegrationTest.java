package com.looped.users;

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
class FollowsListIntegrationTest extends PostgresTestBase {

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
    void followers_list_includes_anon_profiles_and_supports_query() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('FollowListCo','followlist.co') RETURNING id",
                Long.class
        );

        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-viewer", "viewer", companyId);
        long targetUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-target", "target", companyId, "Target"
        );
        long followerUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-follower", "follower", companyId, "Follower"
        );

        long targetPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, targetUserId
        );
        long followerPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, followerUserId
        );
        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, new byte[]{1, 2, 3, 4}, "anon_follower"
        );
        long anonPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, anon_profile_id) VALUES ('anon', ?) RETURNING id",
                Long.class, anonProfileId
        );

        jdbc.update(
                "INSERT INTO principal_follows(follower_principal_id, followee_principal_id, created_at) VALUES (?,?, now() - interval '2 seconds')",
                followerPrincipalId, targetPrincipalId
        );
        jdbc.update(
                "INSERT INTO principal_follows(follower_principal_id, followee_principal_id, created_at) VALUES (?,?, now() - interval '1 seconds')",
                anonPrincipalId, targetPrincipalId
        );

        String auth = "Bearer " + token("uid-viewer");
        mockMvc.perform(get("/v1/users/" + targetUserId + "/followers?limit=10").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].kind", equalTo("anon")))
                .andExpect(jsonPath("$.items[0].anon_profile_id", equalTo((int) anonProfileId)))
                .andExpect(jsonPath("$.items[0].id", equalTo((int) anonProfileId)))
                .andExpect(jsonPath("$.items[1].kind", equalTo("user")))
                .andExpect(jsonPath("$.items[1].user_id", equalTo((int) followerUserId)))
                .andExpect(jsonPath("$.items[1].id", equalTo((int) followerUserId)));

        mockMvc.perform(get("/v1/users/" + targetUserId + "/followers?query=anon_follower&limit=10").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].kind", equalTo("anon")));
    }

    @Test
    void followers_list_forbidden_when_target_hides_follower_count() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('FollowListHide','followlisthide.co') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-viewer", "viewer", companyId);
        long targetUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, show_follower_count) VALUES (?,?,?, false) RETURNING id",
                Long.class, "uid-hidden", "hidden", companyId
        );

        mockMvc.perform(get("/v1/users/" + targetUserId + "/followers")
                        .header("Authorization", "Bearer " + token("uid-viewer")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("forbidden")));
    }

    @Test
    void followers_list_allowed_for_self_when_target_hides_follower_count() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('FollowListSelf','followlistself.co') RETURNING id",
                Long.class
        );
        long selfUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, show_follower_count) VALUES (?,?,?, false) RETURNING id",
                Long.class, "uid-self", "self", companyId
        );

        mockMvc.perform(get("/v1/users/" + selfUserId + "/followers")
                        .header("Authorization", "Bearer " + token("uid-self")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }
}

