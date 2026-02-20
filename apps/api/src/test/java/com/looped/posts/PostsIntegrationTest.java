package com.looped.posts;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
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
class PostsIntegrationTest extends PostgresTestBase {

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
    void create_post_idempotent_and_get() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme', 'acme.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-post", "carol", companyId);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Acme') RETURNING id",
                Long.class);
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid=?", Long.class, "uid-post");
        jdbc.update("INSERT INTO principals(kind, user_id) VALUES ('user', ?)", userId);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                userId, communityId, "manual", true);

        String auth = "Bearer " + token("uid-post");
        String body = "{\"content\":\"hello world\", \"communityId\": " + communityId + "}";

        var r1 = mockMvc.perform(post("/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "k-1")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andReturn();

        String id = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(r1.getResponse().getContentAsString())
                .get("id")
                .asText();

        mockMvc.perform(post("/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "k-1")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(Integer.parseInt(id))));

        mockMvc.perform(get("/v1/posts/" + id)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", equalTo("hello world")));
    }

    @Test
    void create_allows_media_only_post_without_caption() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('AcmeMedia', 'acmemedia.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-media-only", "mira", companyId);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'AcmeMedia') RETURNING id",
                Long.class);
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid=?", Long.class, "uid-media-only");
        jdbc.update("INSERT INTO principals(kind, user_id) VALUES ('user', ?)", userId);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                userId, communityId, "manual", true);
        long mediaId = jdbc.queryForObject(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                userId,
                "media/original/323e4567-e89b-12d3-a456-426614174999",
                "image/jpeg"
        );

        String auth = "Bearer " + token("uid-media-only");
        String body = "{\"communityId\": " + communityId + ", \"mediaAssetId\": " + mediaId + "}";

        mockMvc.perform(post("/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "k-media-only")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content", equalTo("")))
                .andExpect(jsonPath("$.media_asset_id", equalTo((int) mediaId)));
    }

    @Test
    void create_requires_idempotency_key() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Beta', 'beta.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-post2", "dave", companyId);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Beta') RETURNING id",
                Long.class);
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid=?", Long.class, "uid-post2");
        jdbc.update("INSERT INTO principals(kind, user_id) VALUES ('user', ?)", userId);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                userId, communityId, "manual", true);

        String auth = "Bearer " + token("uid-post2");

        mockMvc.perform(post("/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .content("{\"content\":\"no key\", \"communityId\": " + communityId + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_requires_verified_community() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Gamma', 'gamma.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-post3", "gina", companyId);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Finance') RETURNING id",
                Long.class);

        String auth = "Bearer " + token("uid-post3");

        mockMvc.perform(post("/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "k-3")
                        .content("{\"content\":\"need verify\", \"communityId\": " + communityId + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("community_not_verified")))
                .andExpect(jsonPath("$.error_code", equalTo("community_not_verified")))
                .andExpect(jsonPath("$.lockContext.communityId", equalTo((int) communityId)))
                .andExpect(jsonPath("$.primaryUnlockAction.type", equalTo("VERIFY_COMMUNITY")));
    }

    @Test
    void create_in_major_returns_verify_parent_then_join_context() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('MajorPostCo', 'majorpost.co') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-post-major", "majorposter", companyId);
        long majorId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','Computer Science') RETURNING id",
                Long.class
        );
        long schoolId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('school', 'MajorPost University') RETURNING id",
                Long.class
        );

        String auth = "Bearer " + token("uid-post-major");

        mockMvc.perform(post("/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "k-major-locked")
                        .content("{\"content\":\"blocked\", \"communityId\": " + majorId + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("specialization_verification_required")))
                .andExpect(jsonPath("$.error_code", equalTo("specialization_verification_required")))
                .andExpect(jsonPath("$.lockContext.requiredVerificationKind", equalTo("school")))
                .andExpect(jsonPath("$.lockContext.verifyTargetCommunityId", equalTo((int) schoolId)))
                .andExpect(jsonPath("$.lockContext.verifyTargetCommunityName", equalTo("MajorPost University")))
                .andExpect(jsonPath("$.primaryUnlockAction.type", equalTo("VERIFY_PARENT_THEN_JOIN")))
                .andExpect(jsonPath("$.primaryUnlockAction.communityId", equalTo((int) schoolId)))
                .andExpect(jsonPath("$.primaryUnlockAction.specializationId", equalTo((int) majorId)));
    }

    @Test
    void create_requires_community_id() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('MissingCommunityCo', 'missing-community.co') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-missing-community", "missingcommunity", companyId);
        String auth = "Bearer " + token("uid-missing-community");

        mockMvc.perform(post("/v1/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "k-missing-community")
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", equalTo("community_required")));
    }

    @Test
    void get_allows_cross_company() throws Exception {
        long acme = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme', 'acme.com') RETURNING id",
                Long.class);
        long beta = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Beta', 'beta.com') RETURNING id",
                Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-author", "arthur", acme);
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Acme') RETURNING id",
                Long.class);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, acme, communityId, "secret");

        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-reader", "rachel", beta);
        String auth = "Bearer " + token("uid-reader");

        mockMvc.perform(get("/v1/posts/" + postId).header("Authorization", auth))
                .andExpect(status().isOk());
    }
}
