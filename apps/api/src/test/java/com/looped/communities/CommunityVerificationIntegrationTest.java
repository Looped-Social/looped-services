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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class CommunityVerificationIntegrationTest extends PostgresTestBase {

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

    private String tokenWithEmail(String sub, String email) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("http://test-issuer")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject(sub)
                .audience(List.of("test-app"))
                .claim("email", email)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Test
    void email_verification_uses_allowed_domain_and_custom_email() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme', 'acme.com') RETURNING id", Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-verify','vera',?) RETURNING id",
                Long.class,
                companyId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, description) VALUES ('company','Amazon','Amazon') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO community_domains(community_id, domain) VALUES (?,?)", communityId, "amazon.com");

        String auth = "Bearer " + tokenWithEmail("uid-verify", "ignored@gmail.com");
        String startBody = """
                {
                  "method": "email",
                  "email": "alice@amazon.com"
                }
                """;
        var start = mockMvc.perform(post("/v1/communities/" + communityId + "/verification/start")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dev_code", notNullValue()))
                .andReturn();

        String devCode = start.getResponse().getContentAsString().replaceAll(".*\"dev_code\":\"([^\"]+)\".*", "$1");
        String finishBody = """
                {
                  "method": "email",
                  "email": "alice@amazon.com",
                  "code": "%s"
                }
                """.formatted(devCode);
        mockMvc.perform(post("/v1/communities/" + communityId + "/verification/finish")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(finishBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified", equalTo(true)))
                .andExpect(jsonPath("$.status", equalTo("approved")));

        String stored = jdbc.queryForObject(
                "SELECT email FROM verification_requests WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class,
                userId
        );
        org.junit.jupiter.api.Assertions.assertEquals("alice@amazon.com", stored);
    }

    @Test
    void email_verification_rejects_unlisted_domain() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Beta', 'beta.com') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-reject','rhea',?)", companyId);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, description) VALUES ('company','Amazon','Amazon') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO community_domains(community_id, domain) VALUES (?,?)", communityId, "amazon.com");

        String auth = "Bearer " + tokenWithEmail("uid-reject", "ignored@gmail.com");
        String startBody = """
                {
                  "method": "email",
                  "email": "bob@gmail.com"
                }
                """;
        mockMvc.perform(post("/v1/communities/" + communityId + "/verification/start")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", equalTo("email_domain_not_allowed")));
    }

    @Test
    void email_verification_requires_domains_configured() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Gamma', 'gamma.com') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-nodomain','nora',?)", companyId);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, description) VALUES ('company','Acme','Acme') RETURNING id",
                Long.class
        );

        String auth = "Bearer " + tokenWithEmail("uid-nodomain", "ignored@gmail.com");
        String startBody = """
                {
                  "method": "email",
                  "email": "alice@acme.com"
                }
                """;
        mockMvc.perform(post("/v1/communities/" + communityId + "/verification/start")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", equalTo("domains_not_configured")));
    }

    @Test
    void sector_verification_inherits_company_domains() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Delta', 'delta.com') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-sector','sara',?)", companyId);
        long sectorId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, description) VALUES ('sector','Retail','Retail') RETURNING id",
                Long.class
        );
        long companyCommunityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, description) VALUES ('company','Walmart','Walmart') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO community_sector_links(sector_id, company_id) VALUES (?, ?)", sectorId, companyCommunityId);
        jdbc.update("INSERT INTO community_domains(community_id, domain) VALUES (?, ?)", companyCommunityId, "walmart.com");

        String auth = "Bearer " + tokenWithEmail("uid-sector", "ignored@gmail.com");
        String startBody = """
                {
                  "method": "email",
                  "email": "sara@walmart.com"
                }
                """;
        mockMvc.perform(post("/v1/communities/" + sectorId + "/verification/start")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("pending")));
    }
}
