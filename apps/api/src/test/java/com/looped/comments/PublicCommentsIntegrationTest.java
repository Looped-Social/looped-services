package com.looped.comments;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class PublicCommentsIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void signed_out_can_read_comments_and_replies_for_public_post() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('PubCommentsCo', 'pub-comments.co') RETURNING id",
                Long.class
        );
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class,
                "uid-public-comments-author",
                "pubauthor",
                companyId,
                "Public Author"
        );
        long commenterId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class,
                "uid-public-comments-commenter",
                "pubcommenter",
                companyId,
                "Public Commenter"
        );
        long authorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                authorId
        );
        long commenterPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                commenterId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Public Comments Community') RETURNING id",
                Long.class
        );
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility) VALUES (?,?,?,?,?,'public') RETURNING id",
                Long.class,
                authorId,
                authorPrincipalId,
                companyId,
                communityId,
                "Public comments post"
        );
        long topCommentId = jdbc.queryForObject(
                "INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, reply_count, visibility) VALUES (?,?,?,?,?,1,'public') RETURNING id",
                Long.class,
                postId,
                commenterId,
                commenterPrincipalId,
                companyId,
                "Top-level comment"
        );
        long replyId = jdbc.queryForObject(
                "INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, parent_id, visibility) VALUES (?,?,?,?,?,?,'public') RETURNING id",
                Long.class,
                postId,
                authorId,
                authorPrincipalId,
                companyId,
                "Reply comment",
                topCommentId
        );

        mockMvc.perform(get("/v1/public/posts/" + postId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", equalTo(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo((int) topCommentId)))
                .andExpect(jsonPath("$.items[0].author.username", equalTo("pubcommenter")))
                .andExpect(jsonPath("$.items[0].parent_id").doesNotExist())
                .andExpect(jsonPath("$.items[0].author_principal_id").doesNotExist())
                .andExpect(jsonPath("$.items[0].user_liked").doesNotExist())
                .andExpect(jsonPath("$.items[0].author.company_id").doesNotExist())
                .andExpect(jsonPath("$.items[0].author.principal_id").doesNotExist());

        mockMvc.perform(get("/v1/public/comments/" + topCommentId + "/replies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", equalTo(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo((int) replyId)))
                .andExpect(jsonPath("$.items[0].parent_id", equalTo((int) topCommentId)));
    }

    @Test
    void public_comments_and_replies_return_410_when_post_unavailable() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('PubCommentsGoneCo', 'pub-comments-gone.co') RETURNING id",
                Long.class
        );
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class,
                "uid-public-comments-gone-author",
                "goneauthor",
                companyId
        );
        long authorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                authorId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Public Comments Gone Community') RETURNING id",
                Long.class
        );
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility) VALUES (?,?,?,?,?,'quarantined') RETURNING id",
                Long.class,
                authorId,
                authorPrincipalId,
                companyId,
                communityId,
                "Unavailable post"
        );
        long commentId = jdbc.queryForObject(
                "INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content, visibility) VALUES (?,?,?,?,?,'public') RETURNING id",
                Long.class,
                postId,
                authorId,
                authorPrincipalId,
                companyId,
                "Comment on unavailable post"
        );

        mockMvc.perform(get("/v1/public/posts/" + postId + "/comments"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error", equalTo("post_unavailable")));

        mockMvc.perform(get("/v1/public/comments/" + commentId + "/replies"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error", equalTo("post_unavailable")));
    }

    @Test
    void public_comment_endpoints_return_404_for_missing_targets() throws Exception {
        mockMvc.perform(get("/v1/public/posts/999999/comments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", equalTo("post_not_found")));

        mockMvc.perform(get("/v1/public/comments/999999/replies"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", equalTo("comment_not_found")));
    }
}
