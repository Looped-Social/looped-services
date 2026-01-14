package com.looped.media;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app",
        "cloudfront.domain=cdn.example.com"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class MediaResolveIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void resolve_returns_only_public_media_prefix_assets() throws Exception {
        Long publicId = jdbc.queryForObject(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type) VALUES (NULL, ?, ?) RETURNING id",
                Long.class,
                "media/original/123e4567-e89b-12d3-a456-426614174999",
                "image/jpeg"
        );
        Long otherId = jdbc.queryForObject(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type) VALUES (NULL, ?, ?) RETURNING id",
                Long.class,
                "community-logos/logo.png",
                "image/png"
        );

        String body = "{\"ids\":[" + publicId + "," + otherId + "]}";
        mockMvc.perform(post("/v1/media/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", equalTo(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo(publicId.intValue())))
                .andExpect(jsonPath("$.items[0].cdn_url", equalTo("https://cdn.example.com/media/original/123e4567-e89b-12d3-a456-426614174999")));
    }
}

