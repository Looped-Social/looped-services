package com.looped.feedback;

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

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class FeedbackIntegrationTest extends PostgresTestBase {

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
    void create_feedback_stores_row() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-feedback','sam',?) RETURNING id", Long.class, companyId);

        String body = """
                {
                  "title": "Feedback title",
                  "message": "This is a message",
                  "email": "sam@acme.com"
                }
                """;

        mockMvc.perform(post("/v1/feedback")
                        .header("Authorization", "Bearer " + token("uid-feedback"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", equalTo("received")))
                .andExpect(jsonPath("$.id").isNumber());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM feedback WHERE user_id = ? AND title = ? AND message = ? AND email = ?",
                Integer.class, userId, "Feedback title", "This is a message", "sam@acme.com"
        );
        org.junit.jupiter.api.Assertions.assertNotNull(count);
        org.junit.jupiter.api.Assertions.assertEquals(1, count.intValue());
    }

    @Test
    void create_feedback_allows_anonymous() throws Exception {
        String body = """
                {
                  "subject": "Anonymous feedback",
                  "description": "No login required",
                  "email": "anon@example.com"
                }
                """;

        mockMvc.perform(post("/v1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", equalTo("received")))
                .andExpect(jsonPath("$.id").isNumber());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM feedback WHERE user_id IS NULL AND title = ? AND message = ? AND email = ?",
                Integer.class, "Anonymous feedback", "No login required", "anon@example.com"
        );
        org.junit.jupiter.api.Assertions.assertNotNull(count);
        org.junit.jupiter.api.Assertions.assertEquals(1, count.intValue());
    }
}
