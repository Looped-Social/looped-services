package com.looped.media;

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
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app",
        "cloudfront.domain=cdn.example.com",
        "media.callbackSecret=secret123"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class MediaCallbackIntegrationTest extends PostgresTestBase {

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
    void callback_persists_asset_with_valid_signature() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-media", "mike", companyId);

        String key = "media/original/123e4567-e89b-12d3-a456-426614174000";
        String sig = MediaService.hmacSha256Base64("secret123", key);
        String body = "{\"key\":\"" + key + "\",\"mimeType\":\"image/jpeg\",\"width\":640,\"height\":480}";

        mockMvc.perform(post("/v1/media/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("uid-media"))
                        .header("X-Media-Signature", sig)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.mimeType", equalTo("image/jpeg")))
                .andExpect(jsonPath("$.cdnUrl", equalTo("https://cdn.example.com/" + key)))
                .andExpect(jsonPath("$.cdn_url", equalTo("https://cdn.example.com/" + key)));
    }

    @Test
    void callback_allows_anon_actor_without_jwt() throws Exception {
        String key = "media/original/223e4567-e89b-12d3-a456-426614174000";
        String sig = MediaService.hmacSha256Base64("secret123", key);
        String body = "{\"key\":\"" + key + "\",\"mimeType\":\"image/jpeg\",\"width\":640,\"height\":480}";

        mockMvc.perform(post("/v1/media/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor", "anon")
                        .header("X-Media-Signature", sig)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.mimeType", equalTo("image/jpeg")))
                .andExpect(jsonPath("$.cdnUrl", equalTo("https://cdn.example.com/" + key)))
                .andExpect(jsonPath("$.cdn_url", equalTo("https://cdn.example.com/" + key)));
    }

    @Test
    void callback_persists_video_duration_seconds() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme2','acme2.com') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-video", "vicky", companyId);

        String key = "media/original/323e4567-e89b-12d3-a456-426614174000";
        String sig = MediaService.hmacSha256Base64("secret123", key);
        String body = "{\"key\":\"" + key + "\",\"mimeType\":\"video/mp4\",\"durationSeconds\":12}";

        mockMvc.perform(post("/v1/media/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("uid-video"))
                        .header("X-Media-Signature", sig)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.mimeType", equalTo("video/mp4")))
                .andExpect(jsonPath("$.durationSeconds", equalTo(12)))
                .andExpect(jsonPath("$.duration_seconds", equalTo(12)))
                .andExpect(jsonPath("$.cdnUrl", equalTo("https://cdn.example.com/" + key)))
                .andExpect(jsonPath("$.cdn_url", equalTo("https://cdn.example.com/" + key)));
    }
}
