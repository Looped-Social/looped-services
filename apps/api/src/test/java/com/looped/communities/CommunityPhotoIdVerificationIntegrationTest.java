package com.looped.communities;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
        "auth.audience=test-app",
        "email.enabled=false"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class CommunityPhotoIdVerificationIntegrationTest extends PostgresTestBase {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
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
    void photo_id_is_scoped_per_community() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme', 'acme.com') RETURNING id", Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-photo','phoebe',?) RETURNING id",
                Long.class,
                companyId
        );
        long communityA = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, description) VALUES ('school','UNC','UNC') RETURNING id",
                Long.class
        );
        long communityB = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, description) VALUES ('school','USC','USC') RETURNING id",
                Long.class
        );

        String auth = "Bearer " + token("uid-photo");

        mockMvc.perform(get("/v1/communities/" + communityA + "/verification/photo-id/status")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("none")))
                .andExpect(jsonPath("$.method", equalTo("photo_id")));

        var startA = mockMvc.perform(post("/v1/communities/" + communityA + "/verification/photo-id/start")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("pending_upload")))
                .andExpect(jsonPath("$.upload_session_id", notNullValue()))
                .andExpect(jsonPath("$.nonce", notNullValue()))
                .andReturn();

        var startJson = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(startA.getResponse().getContentAsString());
        String sessionA = startJson.get("upload_session_id").asText();
        String nonce = startJson.get("nonce").asText();

        String selfieKey = "verification/photo-id/" + userId + "/" + sessionA + "/selfie.jpg";
        String idFrontKey = "verification/photo-id/" + userId + "/" + sessionA + "/id_front.jpg";

        String submitBody = """
                {
                  "uploadSessionId": "%s",
                  "documents": {
                    "selfieKey": "%s",
                    "idFrontKey": "%s"
                  }
                }
                """.formatted(sessionA, selfieKey, idFrontKey);

        mockMvc.perform(post("/v1/communities/" + communityA + "/verification/photo-id/submit")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("pending_review")))
                .andExpect(jsonPath("$.verification_request_id", notNullValue()));

        String storedMetadata = jdbc.queryForObject(
                "SELECT metadata FROM verification_requests WHERE user_id = ? AND community_id = ? ORDER BY id DESC LIMIT 1",
                String.class,
                userId,
                communityA
        );
        org.junit.jupiter.api.Assertions.assertNotNull(storedMetadata);
        String storedNonce = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(storedMetadata)
                .get("nonce").asText();
        org.junit.jupiter.api.Assertions.assertEquals(nonce, storedNonce);

        // Pending review in community A should not block starting a new submission in community B.
        mockMvc.perform(post("/v1/communities/" + communityB + "/verification/photo-id/start")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("pending_upload")))
                .andExpect(jsonPath("$.upload_session_id", notNullValue()));

        // But it should still block re-submitting for the same community while pending.
        mockMvc.perform(post("/v1/communities/" + communityA + "/verification/photo-id/start")
                        .header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", equalTo("already_pending")));
    }
}
