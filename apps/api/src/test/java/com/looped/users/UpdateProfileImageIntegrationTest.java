package com.looped.users;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app",
        "cloudfront.domain=cdn.example.com"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class UpdateProfileImageIntegrationTest extends PostgresTestBase {

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
    void update_profile_accepts_profile_media_asset_id() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-profile','pat',?) RETURNING id", Long.class, companyId);
        String key = "media/original/123e4567-e89b-12d3-a456-426614174000";
        long mediaId = jdbc.queryForObject("INSERT INTO media_assets(owner_id, s3_key, mime_type) VALUES (?,?,?) RETURNING id", Long.class, userId, key, "image/jpeg");

        String body = "{\"displayName\":\"Pat\",\"bio\":\"Hello\",\"isAnonymous\":false,\"profileMediaAssetId\":" + mediaId + "}";

        mockMvc.perform(put("/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("uid-profile"))
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile_image_url", equalTo("https://cdn.example.com/" + key)));

        String stored = jdbc.queryForObject("SELECT profile_image_url FROM users WHERE id = ?", String.class, userId);
        org.junit.jupiter.api.Assertions.assertEquals("https://cdn.example.com/" + key, stored);
    }

    @Test
    void update_profile_alias_updates_profile_image_url() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-profile-alias','pat2',?) RETURNING id", Long.class, companyId);
        String key = "media/original/alias-profile";
        long mediaId = jdbc.queryForObject("INSERT INTO media_assets(owner_id, s3_key, mime_type) VALUES (?,?,?) RETURNING id", Long.class, userId, key, "image/png");

        String body = "{\"displayName\":\"Pat\",\"bio\":\"Hello\",\"isAnonymous\":false,\"profileMediaAssetId\":" + mediaId + "}";

        mockMvc.perform(put("/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("uid-profile-alias"))
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile_image_url", equalTo("https://cdn.example.com/" + key)));
    }

    @Test
    void update_profile_alias_requires_auth() throws Exception {
        String body = "{\"displayName\":\"Pat\",\"bio\":\"Hello\",\"isAnonymous\":false}";
        mockMvc.perform(put("/users/me").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void update_profile_rejects_media_asset_not_owned() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-actor','alex',?)", companyId);
        long otherUserId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-other','oliver',?) RETURNING id", Long.class, companyId);
        long mediaId = jdbc.queryForObject(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type) VALUES (?,?,?) RETURNING id",
                Long.class,
                otherUserId,
                "media/original/abc",
                "image/png"
        );

        String body = "{\"displayName\":\"Alex\",\"bio\":\"Hello\",\"isAnonymous\":false,\"profileMediaAssetId\":" + mediaId + "}";

        mockMvc.perform(put("/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("uid-actor"))
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("media_asset_forbidden")));
    }

    @Test
    void update_profile_rejects_non_image_media_asset() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-video','val',?) RETURNING id", Long.class, companyId);
        long mediaId = jdbc.queryForObject(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type) VALUES (?,?,?) RETURNING id",
                Long.class,
                userId,
                "media/original/video1",
                "video/mp4"
        );

        String body = "{\"displayName\":\"Val\",\"bio\":\"Hello\",\"isAnonymous\":false,\"profileMediaAssetId\":" + mediaId + "}";

        mockMvc.perform(put("/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("uid-video"))
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", equalTo("invalid_profile_image")));
    }
}
