package com.looped.integration;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class CommentsIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

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
    void create_list_reply_and_like_comments() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('CommentCo','comment.co') RETURNING id", Long.class);
        long postAuthorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-author", "author", companyId, "Author");
        long commenterId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-commenter", "commenter", companyId, "Commenter");
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, postAuthorId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'CommentCo') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                postAuthorId, communityId, "manual", true);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                commenterId, communityId, "manual", true);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, postAuthorId, authorPrincipal, companyId, communityId, "post body");

        String commenterAuth = "Bearer " + token("uid-commenter");
        String authorAuth = "Bearer " + token("uid-author");
        var createResp = mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", commenterAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"first!\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author.id").value((int) commenterId))
                .andExpect(jsonPath("$.likes_count").value(0))
                .andReturn();

        long commentId = mapper.readTree(createResp.getResponse().getContentAsString()).get("id").asLong();
        assertEquals(1, jdbc.queryForObject("SELECT comments_count FROM posts WHERE id=?", Integer.class, postId));

        mockMvc.perform(get("/v1/posts/" + postId + "/comments")
                        .header("Authorization", authorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value((int) commentId))
                .andExpect(jsonPath("$.items[0].author.username").value("commenter"))
                .andExpect(jsonPath("$.items[0].user_liked").value(false))
                .andExpect(jsonPath("$.items[0].liked_by_creator").value(false));

        var replyResp = mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", authorAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"reply\",\"parentId\":" + commentId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parent_id").value((int) commentId))
                .andReturn();

        long replyId = mapper.readTree(replyResp.getResponse().getContentAsString()).get("id").asLong();
        assertEquals(2, jdbc.queryForObject("SELECT comments_count FROM posts WHERE id=?", Integer.class, postId));

        mockMvc.perform(get("/v1/comments/" + commentId + "/replies")
                        .header("Authorization", commenterAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value((int) replyId))
                .andExpect(jsonPath("$.items[0].parent_id").value((int) commentId));

        mockMvc.perform(post("/v1/comments/" + commentId + "/like")
                        .header("Authorization", authorAuth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.likes_count").value(1))
                .andExpect(jsonPath("$.user_liked").value(true))
                .andExpect(jsonPath("$.liked_by_creator").value(true));

        mockMvc.perform(get("/v1/posts/" + postId + "/comments")
                        .header("Authorization", commenterAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].likes_count").value(1))
                .andExpect(jsonPath("$.items[0].parent_id").doesNotExist())
                .andExpect(jsonPath("$.items[0].user_liked").value(false))
                .andExpect(jsonPath("$.items[0].liked_by_creator").value(true));
    }

    @Test
    void user_replies_endpoint_lists_desc() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('RepliesCo','replies.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-r1", "replyuser", companyId, "Replier");
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-author-r1", "author", companyId, "Author");
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'RepliesCo') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                userId, communityId, "manual", true);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                authorId, communityId, "manual", true);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, communityId, "post");

        String auth = "Bearer " + token("uid-r1");
        String authorAuth = "Bearer " + token("uid-author-r1");

        var c1 = mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"first\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long comment1 = mapper.readTree(c1.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"second\",\"parentId\":" + comment1 + "}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/users/" + userId + "/replies")
                        .header("Authorization", authorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].content").value("second"))
                .andExpect(jsonPath("$.items[0].parent_id").value((int) comment1))
                .andExpect(jsonPath("$.items[1].content").value("first"));
    }

    @Test
    void comment_requires_community_verification() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('VerifyCo','verify.co') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-v-author", "vauthor", companyId);
        long commenterId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-v-commenter", "vcommenter", companyId);
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'VerifyCo') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                authorId, communityId, "manual", true);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, communityId, "post");

        mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", "Bearer " + token("uid-v-commenter"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"nope\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("community_not_verified"))
                .andExpect(jsonPath("$.error_code").value("community_not_verified"))
                .andExpect(jsonPath("$.lockContext.communityId").value((int) communityId))
                .andExpect(jsonPath("$.primaryUnlockAction.type").value("VERIFY_COMMUNITY"));
    }

    @Test
    void comment_in_major_returns_verify_parent_then_join_context() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('CommentMajorCo','comment-major.co') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-cm-author", "cmauthor", companyId
        );
        jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-cm-viewer", "cmviewer", companyId
        );
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long majorId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','major','History') RETURNING id",
                Long.class
        );
        long schoolId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('school', 'Comment Major University') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?,?)", authorId, majorId);
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, majorId, "major post"
        );

        mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", "Bearer " + token("uid-cm-viewer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"blocked\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("specialization_verification_required"))
                .andExpect(jsonPath("$.error_code").value("specialization_verification_required"))
                .andExpect(jsonPath("$.lockContext.requiredVerificationKind").value("school"))
                .andExpect(jsonPath("$.lockContext.verifyTargetCommunityId").value((int) schoolId))
                .andExpect(jsonPath("$.lockContext.verifyTargetCommunityName").value("Comment Major University"))
                .andExpect(jsonPath("$.primaryUnlockAction.type").value("VERIFY_PARENT_THEN_JOIN"))
                .andExpect(jsonPath("$.primaryUnlockAction.communityId").value((int) schoolId))
                .andExpect(jsonPath("$.primaryUnlockAction.specializationId").value((int) majorId));
    }

    @Test
    void comment_like_requires_community_verification() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('LikeVerifyCo','like-verify.co') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-lv-author", "lvauthor", companyId);
        long commenterId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-lv-commenter", "lvcommenter", companyId);
        jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-lv-liker", "lvliker", companyId);
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'LikeVerifyCo') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                authorId, communityId, "manual", true);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                commenterId, communityId, "manual", true);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, communityId, "post");

        var createResp = mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", "Bearer " + token("uid-lv-commenter"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"comment\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long commentId = mapper.readTree(createResp.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/v1/comments/" + commentId + "/like")
                        .header("Authorization", "Bearer " + token("uid-lv-liker")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("community_not_verified"));
    }

    @Test
    void comment_like_requires_specialization_join() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('LikeSpecCo','like-spec.co') RETURNING id", Long.class);
        long joinedUserId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-ls-joined", "lsjoined", companyId);
        jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-ls-notjoined", "lsnotjoined", companyId);
        long specializationId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','field','LikeSpecField') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?, ?)", joinedUserId, specializationId);

        String joinedAuth = "Bearer " + token("uid-ls-joined");
        var postResp = mockMvc.perform(post("/v1/posts")
                        .header("Authorization", joinedAuth)
                        .header("Idempotency-Key", "ls-post-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"post\",\"communityId\":" + specializationId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        long postId = mapper.readTree(postResp.getResponse().getContentAsString()).get("id").asLong();

        var commentResp = mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", joinedAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"comment\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long commentId = mapper.readTree(commentResp.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/v1/comments/" + commentId + "/like")
                        .header("Authorization", "Bearer " + token("uid-ls-notjoined")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("specialization_not_joined"));
    }

    @Test
    void comment_author_can_delete_existing_comment_after_verification_expires() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('CommentExpireCo','comment-expire.co') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-ce-author", "ceauthor", companyId);
        long commenterId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-ce-commenter", "cecommenter", companyId);
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'CommentExpireCo') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) VALUES (?,?,?,?, now(), now() + interval '1 day')",
                authorId, communityId, "manual", true);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) VALUES (?,?,?,?, now(), now() + interval '1 day')",
                commenterId, communityId, "manual", true);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, communityId, "post body");

        String commenterAuth = "Bearer " + token("uid-ce-commenter");

        var createResp = mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", commenterAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"before expiry\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long commentId = mapper.readTree(createResp.getResponse().getContentAsString()).get("id").asLong();

        jdbc.update("UPDATE community_verifications SET expires_at = now() - interval '1 day' WHERE user_id = ? AND community_id = ?",
                commenterId, communityId);

        mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", commenterAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"should fail\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("verification_expired"))
                .andExpect(jsonPath("$.error_code").value("verification_expired"));

        mockMvc.perform(delete("/v1/comments/" + commentId)
                        .header("Authorization", commenterAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void edit_delete_and_unlike_comments() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('EditCo','edit.co') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-edit-author", "editauthor", companyId, "Author");
        long commenterId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-edit-commenter", "editcommenter", companyId, "Commenter");
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'EditCo') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                authorId, communityId, "manual", true);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                commenterId, communityId, "manual", true);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, communityId, "post body");

        String commenterAuth = "Bearer " + token("uid-edit-commenter");
        String authorAuth = "Bearer " + token("uid-edit-author");

        var createResp = mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", commenterAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"original\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long commentId = mapper.readTree(createResp.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(put("/v1/comments/" + commentId)
                        .header("Authorization", commenterAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"edited\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("edited"));

        mockMvc.perform(post("/v1/comments/" + commentId + "/like")
                        .header("Authorization", authorAuth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.likes_count").value(1))
                .andExpect(jsonPath("$.user_liked").value(true));

        mockMvc.perform(delete("/v1/comments/" + commentId + "/like")
                        .header("Authorization", authorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likes_count").value(0))
                .andExpect(jsonPath("$.user_liked").value(false))
                .andExpect(jsonPath("$.liked_by_creator").value(false));

        mockMvc.perform(delete("/v1/comments/" + commentId)
                        .header("Authorization", commenterAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        assertEquals(0, jdbc.queryForObject("SELECT comments_count FROM posts WHERE id=?", Integer.class, postId));

        mockMvc.perform(get("/v1/posts/" + postId + "/comments")
                        .header("Authorization", authorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void deleting_top_level_comment_with_replies_keeps_sanitized_tombstone() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('TombstoneCo','tombstone.co') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-ts-author", "tsauthor", companyId, "Author TS");
        long commenterId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-ts-commenter", "tscommenter", companyId, "Commenter TS");
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'TombstoneCo') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                authorId, communityId, "manual", true);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                commenterId, communityId, "manual", true);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, communityId, "post");

        String authorAuth = "Bearer " + token("uid-ts-author");
        String commenterAuth = "Bearer " + token("uid-ts-commenter");

        var topResp = mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", commenterAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"top\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long topId = mapper.readTree(topResp.getResponse().getContentAsString()).get("id").asLong();

        var replyResp = mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", authorAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"child\",\"parentId\":" + topId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        long replyId = mapper.readTree(replyResp.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(delete("/v1/comments/" + topId)
                        .header("Authorization", commenterAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        mockMvc.perform(get("/v1/posts/" + postId + "/comments")
                        .header("Authorization", authorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value((int) topId))
                .andExpect(jsonPath("$.items[0].is_deleted").value(true))
                .andExpect(jsonPath("$.items[0].content").value(""))
                .andExpect(jsonPath("$.items[0].media_asset_id").value(nullValue()))
                .andExpect(jsonPath("$.items[0].reply_count").value(1))
                .andExpect(jsonPath("$.items[0].author_principal_id").value(nullValue()))
                .andExpect(jsonPath("$.items[0].author.id").value(nullValue()))
                .andExpect(jsonPath("$.items[0].author.display_name").value(nullValue()))
                .andExpect(jsonPath("$.items[0].author.username").value(nullValue()))
                .andExpect(jsonPath("$.items[0].author.handle").value(nullValue()))
                .andExpect(jsonPath("$.items[0].author.profile_image_url").value(nullValue()));

        mockMvc.perform(get("/v1/comments/" + topId + "/replies")
                        .header("Authorization", authorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value((int) replyId))
                .andExpect(jsonPath("$.items[0].parent_id").value((int) topId))
                .andExpect(jsonPath("$.items[0].is_deleted").value(false));
    }

    @Test
    void deleting_reply_without_children_hides_reply_and_decrements_parent_count() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('LeafReplyCo','leaf-reply.co') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-lr-author", "lrauthor", companyId, "Author LR");
        long commenterId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-lr-commenter", "lrcommenter", companyId, "Commenter LR");
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'LeafReplyCo') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                authorId, communityId, "manual", true);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                commenterId, communityId, "manual", true);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, communityId, "post");

        String authorAuth = "Bearer " + token("uid-lr-author");
        String commenterAuth = "Bearer " + token("uid-lr-commenter");

        var topResp = mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", commenterAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"top\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long topId = mapper.readTree(topResp.getResponse().getContentAsString()).get("id").asLong();

        var replyResp = mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", authorAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"child\",\"parentId\":" + topId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        long replyId = mapper.readTree(replyResp.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(delete("/v1/comments/" + replyId)
                        .header("Authorization", authorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        mockMvc.perform(get("/v1/comments/" + topId + "/replies")
                        .header("Authorization", commenterAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        assertEquals(0, jdbc.queryForObject("SELECT reply_count FROM comments WHERE id=?", Integer.class, topId));
    }

    @Test
    void deleting_reply_with_children_keeps_sanitized_tombstone_until_children_deleted() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('NestedReplyCo','nested-reply.co') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-nr-author", "nrauthor", companyId, "Author NR");
        long commenterId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-nr-commenter", "nrcommenter", companyId, "Commenter NR");
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'NestedReplyCo') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                authorId, communityId, "manual", true);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                commenterId, communityId, "manual", true);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, communityId, "post");

        String authorAuth = "Bearer " + token("uid-nr-author");
        String commenterAuth = "Bearer " + token("uid-nr-commenter");

        var topResp = mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", commenterAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"top\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long topId = mapper.readTree(topResp.getResponse().getContentAsString()).get("id").asLong();

        var replyResp = mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", authorAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"middle\",\"parentId\":" + topId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        long replyId = mapper.readTree(replyResp.getResponse().getContentAsString()).get("id").asLong();

        var nestedResp = mockMvc.perform(post("/v1/posts/" + postId + "/comments")
                        .header("Authorization", commenterAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"leaf\",\"parentId\":" + replyId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        long nestedId = mapper.readTree(nestedResp.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(delete("/v1/comments/" + replyId)
                        .header("Authorization", authorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        mockMvc.perform(get("/v1/comments/" + topId + "/replies")
                        .header("Authorization", commenterAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value((int) replyId))
                .andExpect(jsonPath("$.items[0].is_deleted").value(true))
                .andExpect(jsonPath("$.items[0].content").value(""))
                .andExpect(jsonPath("$.items[0].author_principal_id").value(nullValue()))
                .andExpect(jsonPath("$.items[0].author.id").value(nullValue()))
                .andExpect(jsonPath("$.items[0].author.display_name").value(nullValue()))
                .andExpect(jsonPath("$.items[0].author.username").value(nullValue()))
                .andExpect(jsonPath("$.items[0].author.handle").value(nullValue()))
                .andExpect(jsonPath("$.items[0].author.profile_image_url").value(nullValue()))
                .andExpect(jsonPath("$.items[0].reply_count").value(1));

        assertEquals(1, jdbc.queryForObject("SELECT reply_count FROM comments WHERE id=?", Integer.class, topId));

        mockMvc.perform(delete("/v1/comments/" + nestedId)
                        .header("Authorization", commenterAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        mockMvc.perform(get("/v1/comments/" + topId + "/replies")
                        .header("Authorization", commenterAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        assertEquals(0, jdbc.queryForObject("SELECT reply_count FROM comments WHERE id=?", Integer.class, topId));
    }
}
