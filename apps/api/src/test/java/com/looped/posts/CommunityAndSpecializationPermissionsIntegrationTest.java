package com.looped.posts;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class CommunityAndSpecializationPermissionsIntegrationTest extends PostgresTestBase {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @org.springframework.test.context.DynamicPropertySource
    static void redisProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

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
    void specialization_requires_join_for_post_comment_like_but_not_repost() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('SpecCo', 'spec.co') RETURNING id",
                Long.class
        );
        long user1Id = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-spec-u1", "specu1", companyId
        );
        long user2Id = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-spec-u2", "specu2", companyId
        );

        long companyCommunityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'SpecCo') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, expires_at) VALUES (?,?,?,?, NULL)",
                user1Id, companyCommunityId, "manual", true
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, expires_at) VALUES (?,?,?,?, NULL)",
                user2Id, companyCommunityId, "manual", true
        );

        long specializationId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','field','Engineering') RETURNING id",
                Long.class
        );

        String auth1 = "Bearer " + token("uid-spec-u1");
        String auth2 = "Bearer " + token("uid-spec-u2");

        mockMvc.perform(get("/v1/communities/" + specializationId + "/permissions").header("Authorization", auth2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requires_join", equalTo(true)))
                .andExpect(jsonPath("$.can_post", equalTo(false)));

        mockMvc.perform(post("/v1/specializations/" + specializationId + "/join").header("Authorization", auth1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.joined").value(true));

        String postBody = "{\"content\":\"hello\", \"communityId\": " + specializationId + "}";
        var created = mockMvc.perform(post("/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth1)
                        .header("Idempotency-Key", "spec-post-1")
                        .content(postBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andReturn();
        long postId = new ObjectMapper()
                .readTree(created.getResponse().getContentAsString())
                .get("id")
                .asLong();

        mockMvc.perform(post("/v1/posts/" + postId + "/like")
                        .header("Authorization", auth2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("specialization_not_joined")));

        mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", auth2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("specialization_not_joined")));

        mockMvc.perform(put("/v1/posts/" + postId + "/repost")
                        .header("Authorization", auth2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.viewer_has_reposted", equalTo(true)));

        mockMvc.perform(post("/v1/specializations/" + specializationId + "/join").header("Authorization", auth2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.joined").value(true));

        mockMvc.perform(post("/v1/posts/" + postId + "/like")
                        .header("Authorization", auth2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", auth2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"joined\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()));

        mockMvc.perform(put("/v1/posts/" + postId + "/repost")
                        .header("Authorization", auth2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewer_has_reposted", equalTo(true)));
    }

    @Test
    void community_verification_takes_effect_without_token_refresh() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('VerifCo', 'verif.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-verif", "verif", companyId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'VerifCo') RETURNING id",
                Long.class
        );

        String auth = "Bearer " + token("uid-verif");
        String body = "{\"content\":\"hello\", \"communityId\": " + communityId + "}";

        mockMvc.perform(post("/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "v-1")
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("community_not_verified")));

        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, expires_at) VALUES (?,?,?,?, NULL)",
                userId, communityId, "manual", true
        );

        mockMvc.perform(post("/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "v-2")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()));
    }
}
