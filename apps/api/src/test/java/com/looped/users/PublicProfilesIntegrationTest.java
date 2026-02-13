package com.looped.users;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class PublicProfilesIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void signed_out_can_read_share_safe_public_profile_by_username() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Public Profile Co', 'public-profile.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name, bio, show_follower_count, profile_image_url) " +
                        "VALUES (?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                "uid-public-profile",
                "wmillen",
                companyId,
                "William Millen",
                "Building Looped.",
                true,
                "https://cdn.example.com/profiles/wmillen.jpg"
        );
        long targetPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                userId
        );

        long followerUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class,
                "uid-public-profile-follower",
                "follower_user",
                companyId
        );
        long followerPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                followerUserId
        );
        jdbc.update(
                "INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)",
                followerPrincipalId,
                targetPrincipalId
        );

        long followeeUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class,
                "uid-public-profile-followee",
                "followee_user",
                companyId
        );
        long followeePrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                followeeUserId
        );
        jdbc.update(
                "INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)",
                targetPrincipalId,
                followeePrincipalId
        );

        long displayCommunityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, short_name) VALUES ('company', 'Costco', 'costco') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) " +
                        "VALUES (?,?,?,true, now(), NULL)",
                userId,
                displayCommunityId,
                "email"
        );

        long displaySpecializationId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, short_name, specialization_type) VALUES ('specialization', 'Retail', 'retail', 'field') RETURNING id",
                Long.class
        );
        jdbc.update(
                "UPDATE users SET display_community_id = ?, display_specialization_id = ? WHERE id = ?",
                displayCommunityId,
                displaySpecializationId,
                userId
        );

        mockMvc.perform(get("/v1/public/profiles/wmillen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) userId)))
                .andExpect(jsonPath("$.username", equalTo("wmillen")))
                .andExpect(jsonPath("$.handle", equalTo("wmillen")))
                .andExpect(jsonPath("$.display_name", equalTo("William Millen")))
                .andExpect(jsonPath("$.bio", equalTo("Building Looped.")))
                .andExpect(jsonPath("$.profile_image_url", equalTo("https://cdn.example.com/profiles/wmillen.jpg")))
                .andExpect(jsonPath("$.display_community_name", equalTo("Costco")))
                .andExpect(jsonPath("$.display_specialization_name", equalTo("Retail")))
                .andExpect(jsonPath("$.show_follower_count", equalTo(true)))
                .andExpect(jsonPath("$.followers_count", equalTo(1)))
                .andExpect(jsonPath("$.following_count", equalTo(1)))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.company_id").doesNotExist())
                .andExpect(jsonPath("$.message_permission").doesNotExist())
                .andExpect(jsonPath("$.verification").doesNotExist());
    }

    @Test
    void username_input_is_normalized_lowercase_and_leading_at() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Normalize Co', 'normalize.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name, show_follower_count) VALUES (?,?,?,?,true) RETURNING id",
                Long.class,
                "uid-normalized-profile",
                "user_name",
                companyId,
                "User Name"
        );

        mockMvc.perform(get("/v1/public/profiles/@User_Name"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) userId)))
                .andExpect(jsonPath("$.username", equalTo("user_name")))
                .andExpect(jsonPath("$.handle", equalTo("user_name")));
    }

    @Test
    void missing_profile_returns_404() throws Exception {
        mockMvc.perform(get("/v1/public/profiles/does_not_exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", equalTo("profile_not_found")))
                .andExpect(jsonPath("$.message", equalTo("Profile not found")));
    }

    @Test
    void unavailable_profile_returns_410() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Unavailable Co', 'unavailable.co') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO users(firebase_uid, handle, company_id, deleted_at, deleted_source) VALUES (?,?,?, now(), 'self')",
                "uid-profile-unavailable",
                "goneprofile",
                companyId
        );

        mockMvc.perform(get("/v1/public/profiles/goneprofile"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error", equalTo("profile_unavailable")))
                .andExpect(jsonPath("$.message", equalTo("Profile is unavailable")));
    }

    @Test
    void follower_counts_are_hidden_when_show_follower_count_is_false() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Hidden Count Co', 'hidden-count.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name, show_follower_count) VALUES (?,?,?,?,false) RETURNING id",
                Long.class,
                "uid-hidden-count-profile",
                "hiddenuser",
                companyId,
                "Hidden User"
        );
        long targetPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                userId
        );
        long followerUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class,
                "uid-hidden-count-follower",
                "hiddenfollower",
                companyId
        );
        long followerPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                followerUserId
        );
        jdbc.update(
                "INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)",
                followerPrincipalId,
                targetPrincipalId
        );

        mockMvc.perform(get("/v1/public/profiles/hiddenuser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.show_follower_count", equalTo(false)))
                .andExpect(jsonPath("$.followers_count", nullValue()))
                .andExpect(jsonPath("$.following_count", nullValue()));
    }

    @Test
    void public_profile_posts_returns_share_safe_posts_with_pagination_shape() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Public Posts Co', 'public-posts.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class,
                "uid-public-profile-posts",
                "postpreview",
                companyId,
                "Post Preview"
        );
        long userPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                userId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, short_name) VALUES ('company', 'Preview Community', 'preview') RETURNING id",
                Long.class
        );
        long publicPostId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility) VALUES (?,?,?,?,?,'public') RETURNING id",
                Long.class,
                userId,
                userPrincipalId,
                companyId,
                communityId,
                "Visible post"
        );
        long pollId = jdbc.queryForObject(
                "INSERT INTO polls(post_id, question, max_selections, closes_at) VALUES (?,?,?, now() + interval '7 days') RETURNING id",
                Long.class,
                publicPostId,
                "Where to meet?"
                ,1
        );
        jdbc.update("INSERT INTO poll_options(poll_id, text, sort_order) VALUES (?,?,?)", pollId, "Cafe", 0);
        jdbc.update("INSERT INTO poll_options(poll_id, text, sort_order) VALUES (?,?,?)", pollId, "Lobby", 1);
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility) VALUES (?,?,?,?,?,'quarantined')",
                userId,
                userPrincipalId,
                companyId,
                communityId,
                "Hidden post"
        );

        mockMvc.perform(get("/v1/public/profiles/postpreview/posts?limit=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", equalTo(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo((int) publicPostId)))
                .andExpect(jsonPath("$.items[0].content", equalTo("Visible post")))
                .andExpect(jsonPath("$.items[0].poll.question", equalTo("Where to meet?")))
                .andExpect(jsonPath("$.items[0].author_id").doesNotExist())
                .andExpect(jsonPath("$.items[0].author_principal_id").doesNotExist())
                .andExpect(jsonPath("$.items[0].company_id").doesNotExist())
                .andExpect(jsonPath("$.items[0].user_liked").doesNotExist())
                .andExpect(jsonPath("$.items[0].viewer_has_reposted").doesNotExist());
    }

    @Test
    void public_profile_reposts_returns_repost_items_with_public_post_payload() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Public Reposts Co', 'public-reposts.co') RETURNING id",
                Long.class
        );
        long reposterUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class,
                "uid-public-profile-reposter",
                "repostpreview",
                companyId,
                "Repost Preview"
        );
        long reposterPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                reposterUserId
        );
        long authorUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class,
                "uid-public-profile-repost-author",
                "originauthor",
                companyId,
                "Origin Author"
        );
        long authorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                authorUserId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Repost Community') RETURNING id",
                Long.class
        );
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility) VALUES (?,?,?,?,?,'public') RETURNING id",
                Long.class,
                authorUserId,
                authorPrincipalId,
                companyId,
                communityId,
                "Original post"
        );
        long repostId = jdbc.queryForObject(
                "INSERT INTO post_reposts(reposter_principal_id, post_id) VALUES (?,?) RETURNING id",
                Long.class,
                reposterPrincipalId,
                postId
        );

        mockMvc.perform(get("/v1/public/profiles/repostpreview/reposts?limit=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", equalTo(1)))
                .andExpect(jsonPath("$.items[0].repost_id", equalTo((int) repostId)))
                .andExpect(jsonPath("$.items[0].reposted_at").exists())
                .andExpect(jsonPath("$.items[0].post.id", equalTo((int) postId)))
                .andExpect(jsonPath("$.items[0].post.content", equalTo("Original post")))
                .andExpect(jsonPath("$.items[0].post.author_id").doesNotExist())
                .andExpect(jsonPath("$.items[0].post.author_principal_id").doesNotExist())
                .andExpect(jsonPath("$.items[0].post.company_id").doesNotExist())
                .andExpect(jsonPath("$.items[0].post.user_liked").doesNotExist())
                .andExpect(jsonPath("$.items[0].post.viewer_has_reposted").doesNotExist());
    }

    @Test
    void public_profile_posts_and_reposts_return_not_found_or_unavailable_consistently() throws Exception {
        mockMvc.perform(get("/v1/public/profiles/no_such_person/posts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", equalTo("profile_not_found")));

        mockMvc.perform(get("/v1/public/profiles/no_such_person/reposts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", equalTo("profile_not_found")));

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Public Unavailable Co', 'public-unavailable.co') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO users(firebase_uid, handle, company_id, disabled_at, disabled_reason) VALUES (?,?,?, now(), 'policy')",
                "uid-disabled-public-profile",
                "disabledpreview",
                companyId
        );

        mockMvc.perform(get("/v1/public/profiles/disabledpreview/posts"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error", equalTo("profile_unavailable")));

        mockMvc.perform(get("/v1/public/profiles/disabledpreview/reposts"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error", equalTo("profile_unavailable")));
    }
}
