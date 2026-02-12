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
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class PublicPostsIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void signed_out_can_read_public_share_safe_post() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ShareCo', 'share.co') RETURNING id",
                Long.class);
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name, profile_image_url) VALUES (?,?,?,?,?) RETURNING id",
                Long.class,
                "uid-share-author",
                "shareauthor",
                companyId,
                "Share Author",
                "https://cdn.example.com/profiles/shareauthor.jpg"
        );
        long authorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                authorId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, short_name) VALUES ('company', 'Share Community', 'share-community') RETURNING id",
                Long.class
        );
        long mediaId = jdbc.queryForObject(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type, visibility) VALUES (?, ?, ?, 'public') RETURNING id",
                Long.class,
                authorId,
                "media/original/share-media-1",
                "image/jpeg"
        );
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, media_asset_id, likes_count, comments_count, share_count, visibility) " +
                        "VALUES (?,?,?,?,?,?,?,?,?, 'public') RETURNING id",
                Long.class,
                authorId,
                authorPrincipalId,
                companyId,
                communityId,
                "Shareable content",
                mediaId,
                7,
                2,
                5
        );

        mockMvc.perform(get("/v1/public/posts/" + postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) postId)))
                .andExpect(jsonPath("$.content", equalTo("Shareable content")))
                .andExpect(jsonPath("$.author_handle", equalTo("shareauthor")))
                .andExpect(jsonPath("$.author_display_name", equalTo("Share Author")))
                .andExpect(jsonPath("$.community_name", equalTo("Share Community")))
                .andExpect(jsonPath("$.community_short_name", equalTo("share-community")))
                .andExpect(jsonPath("$.likes_count", equalTo(7)))
                .andExpect(jsonPath("$.comments_count", equalTo(2)))
                .andExpect(jsonPath("$.share_count", equalTo(5)))
                .andExpect(jsonPath("$.media_asset_ids[0]", equalTo((int) mediaId)))
                .andExpect(jsonPath("$.author_id").doesNotExist())
                .andExpect(jsonPath("$.author_principal_id").doesNotExist())
                .andExpect(jsonPath("$.company_id").doesNotExist())
                .andExpect(jsonPath("$.anon_profile_id").doesNotExist())
                .andExpect(jsonPath("$.user_liked").doesNotExist())
                .andExpect(jsonPath("$.is_saved").doesNotExist())
                .andExpect(jsonPath("$.viewer_has_reposted").doesNotExist())
                .andExpect(jsonPath("$.is_under_review").doesNotExist());
    }

    @Test
    void public_post_not_found_returns_404() throws Exception {
        mockMvc.perform(get("/v1/public/posts/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", equalTo("post_not_found")));
    }

    @Test
    void removed_or_not_shareable_post_returns_410() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('GoneCo', 'gone.co') RETURNING id",
                Long.class);
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class,
                "uid-gone-author",
                "goneauthor",
                companyId
        );
        long authorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                authorId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Gone Community') RETURNING id",
                Long.class
        );
        long removedPostId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility, removed_at) " +
                        "VALUES (?,?,?,?,?,'public', now()) RETURNING id",
                Long.class,
                authorId,
                authorPrincipalId,
                companyId,
                communityId,
                "Removed content"
        );
        long quarantinedPostId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility) " +
                        "VALUES (?,?,?,?,?,'quarantined') RETURNING id",
                Long.class,
                authorId,
                authorPrincipalId,
                companyId,
                communityId,
                "Quarantined content"
        );

        mockMvc.perform(get("/v1/public/posts/" + removedPostId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error", equalTo("post_unavailable")));

        mockMvc.perform(get("/v1/public/posts/" + quarantinedPostId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error", equalTo("post_unavailable")));
    }

    @Test
    void signed_out_can_resolve_media_from_public_post() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('MediaShareCo', 'mediashare.co') RETURNING id",
                Long.class);
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class,
                "uid-media-share-author",
                "mediashareauthor",
                companyId
        );
        long authorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                authorId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Media Share Community') RETURNING id",
                Long.class
        );
        long mediaId = jdbc.queryForObject(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type, visibility) VALUES (?, ?, ?, 'public') RETURNING id",
                Long.class,
                authorId,
                "media/original/share-media-2",
                "image/jpeg"
        );
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, media_asset_id, visibility) " +
                        "VALUES (?,?,?,?,?,?,'public') RETURNING id",
                Long.class,
                authorId,
                authorPrincipalId,
                companyId,
                communityId,
                "Media share post",
                mediaId
        );

        var publicResp = mockMvc.perform(get("/v1/public/posts/" + postId))
                .andExpect(status().isOk())
                .andReturn();
        long returnedMediaId = objectMapper.readTree(publicResp.getResponse().getContentAsString())
                .get("media_asset_ids")
                .get(0)
                .asLong();

        mockMvc.perform(post("/v1/media/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + returnedMediaId + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", equalTo(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo((int) mediaId)))
                .andExpect(jsonPath("$.items[0].cdnUrl", equalTo("https://cdn.example.com/media/original/share-media-2")));
    }
}
