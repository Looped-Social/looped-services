package com.looped.verification;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app",
        "verification.echo-code=true"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class VerificationIntegrationTest extends PostgresTestBase {

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
    void email_verification_flow() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme', 'acme.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-verify", "vera", companyId);

        String auth = "Bearer " + token("uid-verify");

        var start = mockMvc.perform(post("/v1/verification/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .content("{\"method\":\"email\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("pending")))
                .andExpect(jsonPath("$.dev_code", notNullValue()))
                .andReturn();

        String code = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(start.getResponse().getContentAsString()).get("dev_code").asText();

        mockMvc.perform(post("/v1/verification/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .content("{\"method\":\"email\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified", equalTo(true)));

        Integer verified = jdbc.queryForObject("SELECT verified::int FROM verifications v JOIN users u ON v.user_id=u.id WHERE u.firebase_uid=?",
                Integer.class, "uid-verify");
        org.junit.jupiter.api.Assertions.assertEquals(1, verified);
    }

    @Test
    void video_verification_flow() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Beta', 'beta.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-video", "vicky", companyId);

        String auth = "Bearer " + token("uid-video");

        mockMvc.perform(post("/v1/verification/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .content("{\"method\":\"video\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("pending")));

        mockMvc.perform(post("/v1/verification/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .content("{\"method\":\"video\",\"mediaKey\":\"media/original/fake\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified", equalTo(false)))
                .andExpect(jsonPath("$.status", equalTo("pending")));
    }

    @Test
    void thirdparty_verification_flow() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Gamma', 'gamma.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-third", "tina", companyId);

        String auth = "Bearer " + token("uid-third");

        var start = mockMvc.perform(post("/v1/verification/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .content("{\"method\":\"thirdparty\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("pending")))
                .andExpect(jsonPath("$.session_id", notNullValue()))
                .andReturn();

        String sessionId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(start.getResponse().getContentAsString()).get("session_id").asText();
        // Token validation is stubbed to accept any non-blank token; service requires session exists
        mockMvc.perform(post("/v1/verification/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .content("{\"method\":\"thirdparty\",\"token\":\"tok-abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified", equalTo(true)));
    }
}
