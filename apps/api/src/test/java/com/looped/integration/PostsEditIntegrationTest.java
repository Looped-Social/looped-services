package com.looped.integration;

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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class PostsEditIntegrationTest extends PostgresTestBase {

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
    void author_can_edit_own_post() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('EditCo','edit.co') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-edit-author", "editauthor", companyId, "Author");
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'EditCo') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                authorId, communityId, "manual", true);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, communityId, "original");

        String authorAuth = "Bearer " + token("uid-edit-author");

        mockMvc.perform(put("/v1/posts/" + postId)
                        .header("Authorization", authorAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"edited\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("edited"));

        mockMvc.perform(get("/v1/posts/" + postId)
                        .header("Authorization", authorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("edited"));
    }

    @Test
    void non_author_cannot_edit_post() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('EditCo2','edit2.co') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-edit2-author", "editauthor2", companyId, "Author");
        long otherId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-edit2-other", "editother2", companyId, "Other");
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'EditCo2') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                authorId, communityId, "manual", true);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                otherId, communityId, "manual", true);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, communityId, "original");

        String otherAuth = "Bearer " + token("uid-edit2-other");

        mockMvc.perform(put("/v1/posts/" + postId)
                        .header("Authorization", otherAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"edited\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(equalTo("forbidden")));
    }

    @Test
    void author_can_remove_media_while_editing_post() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('EditCo3','edit3.co') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-edit3-author", "editauthor3", companyId, "Author");
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'EditCo3') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                authorId, communityId, "manual", true);
        long mediaId = jdbc.queryForObject(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type) VALUES (?,?,?) RETURNING id",
                Long.class,
                authorId,
                "media/original/edit-remove-1",
                "image/jpeg"
        );
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, media_asset_id) VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class,
                authorId,
                authorPrincipal,
                companyId,
                communityId,
                "with media",
                mediaId
        );
        jdbc.update("INSERT INTO post_media_assets(post_id, media_asset_id, sort_order) VALUES (?,?,0)", postId, mediaId);

        String authorAuth = "Bearer " + token("uid-edit3-author");

        mockMvc.perform(put("/v1/posts/" + postId)
                        .header("Authorization", authorAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"edited no media\",\"remove_media\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("edited no media"))
                .andExpect(jsonPath("$.media_asset_id").value(nullValue()))
                .andExpect(jsonPath("$.media_asset_ids").value(nullValue()));

        Long postMediaAssetId = jdbc.queryForObject("SELECT media_asset_id FROM posts WHERE id = ?", Long.class, postId);
        Integer mediaLinks = jdbc.queryForObject("SELECT COUNT(1) FROM post_media_assets WHERE post_id = ?", Integer.class, postId);
        assertNull(postMediaAssetId);
        assertEquals(0, mediaLinks);
    }
}
