package com.looped.devices;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.JwsHeader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

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
class DevicesIntegrationTest extends PostgresTestBase {

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
    void register_device_is_idempotent_by_token() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Acme', 'acme.com') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)",
                "uid-dev", "bob", companyId);

        String body = "{\"apnsToken\":\"tkn-123\",\"platform\":\"ios\"}";
        String auth = "Bearer " + token("uid-dev");

        var res1 = mockMvc.perform(post("/v1/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andReturn();

        String id = res1.getResponse().getContentAsString().replaceAll(".*\"id\":(\d+).*", "$1");

        mockMvc.perform(post("/v1/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "abc-123")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo(Integer.parseInt(id))));
    }

    @Test
    void requires_provisioned_user() throws Exception {
        String body = "{\"apnsToken\":\"tkn-xyz\",\"platform\":\"ios\"}";
        String auth = "Bearer " + token("uid-nonexistent");

        mockMvc.perform(post("/v1/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", auth)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", equalTo("user_not_provisioned")));
    }
}

