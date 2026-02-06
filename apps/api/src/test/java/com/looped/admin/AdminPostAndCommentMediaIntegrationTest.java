package com.looped.admin;

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

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app",
        "cloudfront.domain=test-cdn.looped"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class AdminPostAndCommentMediaIntegrationTest extends PostgresTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminUsersRepository admins;

    private String token(String sub, String email) {
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
    void admin_post_get_includes_media_urls() throws Exception {
        admins.insert(null, "admin-posts@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_POSTS));
        String auth = "Bearer " + token("admin-posts", "admin-posts@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('MediaCo','media.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-media-user", "mediauser", companyId
        );
        long principalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, userId
        );

        long thumbId = jdbc.queryForObject(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type) VALUES (?,?,?) RETURNING id",
                Long.class, userId, "media/original/thumb-1", "image/jpeg"
        );
        long videoId = jdbc.queryForObject(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type, thumbnail_media_asset_id) VALUES (?,?,?,?) RETURNING id",
                Long.class, userId, "media/original/video-1", "video/mp4", thumbId
        );

        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, media_asset_id) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, userId, principalId, companyId, "post with media", videoId
        );

        mockMvc.perform(get("/v1/admin/posts/" + postId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media", hasSize(1)))
                .andExpect(jsonPath("$.media[0].content_type", equalTo("video/mp4")))
                .andExpect(jsonPath("$.media[0].cdn_url").value("https://test-cdn.looped/media/original/video-1"))
                .andExpect(jsonPath("$.media[0].thumbnail_url").value("https://test-cdn.looped/media/original/thumb-1"));
    }

    @Test
    void admin_comment_get_includes_media_urls() throws Exception {
        admins.insert(null, "admin-reports@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_REPORTS));
        String auth = "Bearer " + token("admin-reports", "admin-reports@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('CmtCo','cmt.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, display_name, company_id) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-cmt-user", "cmtuser", "Comment User", companyId
        );
        long principalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, userId
        );
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, userId, principalId, companyId, "post"
        );
        long imgId = jdbc.queryForObject(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type) VALUES (?,?,?) RETURNING id",
                Long.class, userId, "media/original/img-1", "image/jpeg"
        );
        long commentId = jdbc.queryForObject(
                "INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, media_asset_id) VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class, postId, userId, principalId, companyId, "comment", imgId
        );

        mockMvc.perform(get("/v1/admin/comments/" + commentId)
                        .header("Authorization", auth)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) commentId)))
                .andExpect(jsonPath("$.author_handle", equalTo("cmtuser")))
                .andExpect(jsonPath("$.media", hasSize(1)))
                .andExpect(jsonPath("$.media[0].content_type", equalTo("image/jpeg")))
                .andExpect(jsonPath("$.media[0].cdn_url").value("https://test-cdn.looped/media/original/img-1"));
    }
}

