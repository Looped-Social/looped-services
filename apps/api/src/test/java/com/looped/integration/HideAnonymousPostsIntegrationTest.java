package com.looped.integration;

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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class HideAnonymousPostsIntegrationTest extends PostgresTestBase {

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
    void hide_anonymous_posts_filters_everyone_else_but_not_self() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);

        long viewerId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, is_anonymous) VALUES (?,?,?, true) RETURNING id",
                Long.class, "uid-viewer", "viewer", companyId
        );
        long userBId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-b", "bravo", companyId
        );
        long userCId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, is_anonymous) VALUES (?,?,?, true) RETURNING id",
                Long.class, "uid-c", "charlie", companyId
        );

        long viewerPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, viewerId);
        long principalB = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, userBId);
        long principalC = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, userCId);

        long postA = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, viewerId, viewerPrincipal, companyId, "viewer-post"
        );
        long postB = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, userBId, principalB, companyId, "bravo-post"
        );
        long postC = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, userCId, principalC, companyId, "charlie-post"
        );

        long anonProfileId = jdbc.queryForObject(
                "INSERT INTO anonymous_profiles(company_id, public_key, handle) VALUES (?,?,?) RETURNING id",
                Long.class, companyId, new byte[]{1, 2, 3}, "anon1"
        );
        long anonPrincipal = jdbc.queryForObject(
                "INSERT INTO principals(kind, anon_profile_id) VALUES ('anon', ?) RETURNING id",
                Long.class, anonProfileId
        );
        long anonPost = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, is_anon, anon_profile_id, anon_company_id) " +
                        "VALUES (NULL, ?, ?, ?, true, ?, ?) RETURNING id",
                Long.class, anonPrincipal, companyId, "anon-post", anonProfileId, companyId
        );

        String auth = "Bearer " + token("uid-viewer");

        mockMvc.perform(get("/v1/content/preferences").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.hide_anonymous_posts").value(false));

        mockMvc.perform(get("/v1/feed?mode=new&limit=50").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(4)));

        mockMvc.perform(put("/v1/content/preferences")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hideAnonymousPosts\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.hide_anonymous_posts").value(true));

        mockMvc.perform(get("/v1/feed?mode=new&limit=50").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder((int) postA, (int) postB)));

        mockMvc.perform(get("/v1/posts/" + postA).header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/posts/" + postB).header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/posts/" + postC).header("Authorization", auth))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/posts/" + anonPost).header("Authorization", auth))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/v1/users/" + viewerId + "/posts?limit=50").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value((int) postA));

        mockMvc.perform(get("/v1/users/" + userCId + "/posts?limit=50").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }
}

