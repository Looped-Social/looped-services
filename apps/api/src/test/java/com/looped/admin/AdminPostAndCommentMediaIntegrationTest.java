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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Test
    void admin_can_remove_and_restore_comment() throws Exception {
        long adminId = admins.insert(null, "admin-mod@looped.com", "admin", "active",
                List.of(AdminPermissions.REMOVE_POST));
        String auth = "Bearer " + token("admin-mod", "admin-mod@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ModerationCo','moderation.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-mod-user", "moduser", companyId
        );
        long principalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, userId
        );
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, comments_count) VALUES (?,?,?,?,1) RETURNING id",
                Long.class, userId, principalId, companyId, "post"
        );
        long commentId = jdbc.queryForObject(
                "INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, postId, userId, principalId, companyId, "comment"
        );

        mockMvc.perform(post("/v1/admin/comments/" + commentId + "/remove")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"policy_violation\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("removed")));

        assertEquals(0, jdbc.queryForObject("SELECT comments_count FROM posts WHERE id = ?", Integer.class, postId));
        assertEquals("policy_violation", jdbc.queryForObject("SELECT removed_reason FROM comments WHERE id = ?", String.class, commentId));
        assertEquals(adminId, jdbc.queryForObject("SELECT removed_by FROM comments WHERE id = ?", Long.class, commentId));

        mockMvc.perform(post("/v1/admin/comments/" + commentId + "/restore")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("active")));

        assertEquals(1, jdbc.queryForObject("SELECT comments_count FROM posts WHERE id = ?", Integer.class, postId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE id = ? AND removed_at IS NULL AND removed_by IS NULL AND removed_reason IS NULL", Integer.class, commentId));
    }

    @Test
    void removing_post_resolves_all_open_reports_for_post() throws Exception {
        long adminId = admins.insert(null, "admin-post-remove@looped.com", "admin", "active",
                List.of(AdminPermissions.REMOVE_POST));
        String auth = "Bearer " + token("admin-post-remove", "admin-post-remove@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ReportsPostCo','reportspost.co') RETURNING id",
                Long.class
        );
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-reports-post-author", "reportpostauthor", companyId
        );
        long authorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, authorId
        );
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, "post to remove"
        );
        long reporter1 = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-reports-post-reporter-1", "reportpostr1", companyId
        );
        long reporter2 = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-reports-post-reporter-2", "reportpostr2", companyId
        );
        long open1 = jdbc.queryForObject(
                "INSERT INTO reports(target_type, target_id, reporter_id, reason, status) VALUES ('post', ?, ?, 'spam', 'open') RETURNING id",
                Long.class, postId, reporter1
        );
        long open2 = jdbc.queryForObject(
                "INSERT INTO reports(target_type, target_id, reporter_id, reason, status) VALUES ('post', ?, ?, 'abuse', 'open') RETURNING id",
                Long.class, postId, reporter2
        );
        long alreadyResolved = jdbc.queryForObject(
                "INSERT INTO reports(target_type, target_id, reporter_id, reason, status, resolved_at, resolved_reason) " +
                        "VALUES ('post', ?, ?, 'old', 'resolved', now(), 'already_reviewed') RETURNING id",
                Long.class, postId, reporter1
        );

        mockMvc.perform(post("/v1/admin/posts/" + postId + "/remove")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"policy_violation\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("removed")));

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reports WHERE target_type = 'post' AND target_id = ? AND status = 'open'",
                Integer.class, postId
        ));
        assertEquals("resolved", jdbc.queryForObject("SELECT status FROM reports WHERE id = ?", String.class, open1));
        assertEquals("resolved", jdbc.queryForObject("SELECT status FROM reports WHERE id = ?", String.class, open2));
        assertEquals(adminId, jdbc.queryForObject("SELECT resolved_by FROM reports WHERE id = ?", Long.class, open1));
        assertEquals(adminId, jdbc.queryForObject("SELECT resolved_by FROM reports WHERE id = ?", Long.class, open2));
        assertEquals("policy_violation", jdbc.queryForObject("SELECT resolved_reason FROM reports WHERE id = ?", String.class, open1));
        assertEquals("policy_violation", jdbc.queryForObject("SELECT resolved_reason FROM reports WHERE id = ?", String.class, open2));
        assertEquals("already_reviewed", jdbc.queryForObject("SELECT resolved_reason FROM reports WHERE id = ?", String.class, alreadyResolved));
    }

    @Test
    void removing_comment_resolves_all_open_reports_for_comment() throws Exception {
        long adminId = admins.insert(null, "admin-comment-remove@looped.com", "admin", "active",
                List.of(AdminPermissions.REMOVE_POST));
        String auth = "Bearer " + token("admin-comment-remove", "admin-comment-remove@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('ReportsCommentCo','reportscomment.co') RETURNING id",
                Long.class
        );
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-reports-comment-author", "reportcommentauthor", companyId
        );
        long authorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, authorId
        );
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, "post for comment reports"
        );
        long commentId = jdbc.queryForObject(
                "INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, postId, authorId, authorPrincipalId, companyId, "comment to remove"
        );
        long reporter1 = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-reports-comment-reporter-1", "reportcommentr1", companyId
        );
        long reporter2 = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-reports-comment-reporter-2", "reportcommentr2", companyId
        );
        long open1 = jdbc.queryForObject(
                "INSERT INTO reports(target_type, target_id, reporter_id, reason, status) VALUES ('comment', ?, ?, 'spam', 'open') RETURNING id",
                Long.class, commentId, reporter1
        );
        long open2 = jdbc.queryForObject(
                "INSERT INTO reports(target_type, target_id, reporter_id, reason, status) VALUES ('comment', ?, ?, 'abuse', 'open') RETURNING id",
                Long.class, commentId, reporter2
        );

        mockMvc.perform(post("/v1/admin/comments/" + commentId + "/remove")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"policy_violation\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("removed")));

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reports WHERE target_type = 'comment' AND target_id = ? AND status = 'open'",
                Integer.class, commentId
        ));
        assertEquals("resolved", jdbc.queryForObject("SELECT status FROM reports WHERE id = ?", String.class, open1));
        assertEquals("resolved", jdbc.queryForObject("SELECT status FROM reports WHERE id = ?", String.class, open2));
        assertEquals(adminId, jdbc.queryForObject("SELECT resolved_by FROM reports WHERE id = ?", Long.class, open1));
        assertEquals(adminId, jdbc.queryForObject("SELECT resolved_by FROM reports WHERE id = ?", Long.class, open2));
        assertEquals("policy_violation", jdbc.queryForObject("SELECT resolved_reason FROM reports WHERE id = ?", String.class, open1));
        assertEquals("policy_violation", jdbc.queryForObject("SELECT resolved_reason FROM reports WHERE id = ?", String.class, open2));
    }

    @Test
    void admin_can_fetch_removed_post_detail_when_author_is_deleted() throws Exception {
        admins.insert(null, "admin-removed-post@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_POSTS));
        String auth = "Bearer " + token("admin-removed-post", "admin-removed-post@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('RemovedAuthorCo','removedauthor.co') RETURNING id",
                Long.class
        );
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-removed-author", "removedauthor", companyId
        );
        long authorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, authorId
        );
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, removed_at, removed_reason) " +
                        "VALUES (?,?,?,?,now(),'policy_violation') RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, "removed post content"
        );
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", authorId);

        mockMvc.perform(get("/v1/admin/posts/" + postId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) postId)))
                .andExpect(jsonPath("$.author_id", equalTo((int) authorId)))
                .andExpect(jsonPath("$.content", equalTo("removed post content")))
                .andExpect(jsonPath("$.removed_reason", equalTo("policy_violation")));
    }

    @Test
    void admin_comment_detail_always_includes_removed_keys_when_not_removed() throws Exception {
        admins.insert(null, "admin-comment-shape@looped.com", "admin", "active",
                List.of(AdminPermissions.VIEW_REPORTS));
        String auth = "Bearer " + token("admin-comment-shape", "admin-comment-shape@looped.com");

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('CommentShapeCo','commentshape.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-comment-shape-user", "commentshapeuser", companyId
        );
        long principalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, userId
        );
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, userId, principalId, companyId, "shape post"
        );
        long commentId = jdbc.queryForObject(
                "INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, postId, userId, principalId, companyId, "shape comment"
        );

        mockMvc.perform(get("/v1/admin/comments/" + commentId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed_at", nullValue()))
                .andExpect(jsonPath("$.removed_by", nullValue()))
                .andExpect(jsonPath("$.removed_reason", nullValue()));
    }
}
