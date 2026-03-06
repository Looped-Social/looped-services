package com.looped.posts;

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
class LikesIntegrationTest extends PostgresTestBase {

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
    void like_is_idempotent_and_updates_count() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-like-main", "zoe", companyId);
        long principalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, userId);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, userId, principalId, companyId, "hi");

        String auth = "Bearer " + token("uid-like-main");

        mockMvc.perform(post("/v1/posts/" + postId + "/like")
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.likes_count", equalTo(1)));

        mockMvc.perform(post("/v1/posts/" + postId + "/like")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likes_count", equalTo(1)));

        mockMvc.perform(get("/v1/posts/" + postId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likes_count", equalTo(1)));
    }

    @Test
    void like_404_on_missing_post() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme2','acme2.com') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-like2", "yuki", companyId);
        String auth = "Bearer " + token("uid-like2");

        mockMvc.perform(post("/v1/posts/999999/like")
                        .header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void like_allows_cross_company() throws Exception {
        long acme = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme3','acme3.com') RETURNING id", Long.class);
        long beta = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Beta3','beta3.com') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-like-a", "amy", acme);
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-like-b", "ben", beta);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, acme, "hello");

        String auth = "Bearer " + token("uid-like-b");
        mockMvc.perform(post("/v1/posts/" + postId + "/like").header("Authorization", auth))
                .andExpect(status().isCreated());
    }

    @Test
    void like_requires_community_verification() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme4','acme4.com') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-like4-author", "like4author", companyId);
        jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-like4-viewer", "like4viewer", companyId);
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'Acme4') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                authorId, communityId, "manual", true);
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, communityId, "secured post"
        );

        mockMvc.perform(post("/v1/posts/" + postId + "/like")
                        .header("Authorization", "Bearer " + token("uid-like4-viewer")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("community_not_verified")))
                .andExpect(jsonPath("$.error_code", equalTo("community_not_verified")))
                .andExpect(jsonPath("$.lockContext.communityId", equalTo((int) communityId)))
                .andExpect(jsonPath("$.primaryUnlockAction.type", equalTo("VERIFY_COMMUNITY")));
    }

    @Test
    void like_in_field_returns_verify_parent_then_join_context() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('MajorLikeCo','majorlike.co') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-like-major-author", "likemajorauthor", companyId
        );
        jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-like-major-viewer", "likemajorviewer", companyId
        );
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long fieldId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','field','Economics') RETURNING id",
                Long.class
        );
        long companyCommunityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'MajorLikeCo') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?,?)", authorId, fieldId);
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, fieldId, "field locked post"
        );

        mockMvc.perform(post("/v1/posts/" + postId + "/like")
                        .header("Authorization", "Bearer " + token("uid-like-major-viewer")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("specialization_verification_required")))
                .andExpect(jsonPath("$.error_code", equalTo("specialization_verification_required")))
                .andExpect(jsonPath("$.lockContext.requiredVerificationKind", equalTo("company")))
                .andExpect(jsonPath("$.lockContext.verifyTargetCommunityId", equalTo((int) companyCommunityId)))
                .andExpect(jsonPath("$.lockContext.verifyTargetCommunityName", equalTo("MajorLikeCo")))
                .andExpect(jsonPath("$.primaryUnlockAction.type", equalTo("VERIFY_PARENT_THEN_JOIN")))
                .andExpect(jsonPath("$.primaryUnlockAction.communityId", equalTo((int) companyCommunityId)))
                .andExpect(jsonPath("$.primaryUnlockAction.specializationId", equalTo((int) fieldId)));
    }
}
