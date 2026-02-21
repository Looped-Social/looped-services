package com.looped.communities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class PublicCommunitiesIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void public_community_detail_success() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Public Community Co', 'public-community.co') RETURNING id",
                Long.class
        );
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class,
                "uid-public-community-member",
                "publiccommunitymember",
                companyId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, short_name, description, image_url, specialization_type) " +
                        "VALUES ('specialization', 'Public Community', 'pubcomm', 'Public desc', 'https://cdn.example.com/community.jpg', 'field') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?,?)",
                userId,
                communityId
        );

        mockMvc.perform(get("/v1/public/communities/" + communityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", equalTo((int) communityId)))
                .andExpect(jsonPath("$.name", equalTo("Public Community")))
                .andExpect(jsonPath("$.short_name", equalTo("pubcomm")))
                .andExpect(jsonPath("$.description", equalTo("Public desc")))
                .andExpect(jsonPath("$.image_url", equalTo("https://cdn.example.com/community.jpg")))
                .andExpect(jsonPath("$.member_count", equalTo(1)))
                .andExpect(jsonPath("$.kind", equalTo("specialization")))
                .andExpect(jsonPath("$.specialization_type", equalTo("field")));
    }

    @Test
    void public_community_detail_returns_404_when_missing() throws Exception {
        mockMvc.perform(get("/v1/public/communities/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", equalTo("community_not_found")))
                .andExpect(jsonPath("$.message", equalTo("Community not found")));
    }

    @Test
    void public_community_detail_returns_410_when_unavailable() throws Exception {
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('unknown', 'Unshareable Community') RETURNING id",
                Long.class
        );

        mockMvc.perform(get("/v1/public/communities/" + communityId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error", equalTo("community_unavailable")))
                .andExpect(jsonPath("$.message", equalTo("Community is unavailable")));
    }

    @Test
    void public_community_posts_success_pagination_and_shareable_visibility() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Public Posts Community Co', 'public-posts-community.co') RETURNING id",
                Long.class
        );
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class,
                "uid-public-community-post-author",
                "publiccommauthor",
                companyId,
                "Public Community Author"
        );
        long authorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                authorId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, short_name) VALUES ('company', 'Public Posts Community', 'public-posts-community') RETURNING id",
                Long.class
        );

        long olderPublicPostId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility, created_at) " +
                        "VALUES (?,?,?,?,?,'public', now() - interval '2 days') RETURNING id",
                Long.class,
                authorId,
                authorPrincipalId,
                companyId,
                communityId,
                "Older visible post"
        );
        long newerPublicPostId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility, created_at) " +
                        "VALUES (?,?,?,?,?,'public', now() - interval '1 day') RETURNING id",
                Long.class,
                authorId,
                authorPrincipalId,
                companyId,
                communityId,
                "Newer visible post"
        );
        long pollId = jdbc.queryForObject(
                "INSERT INTO polls(post_id, question, max_selections, closes_at) VALUES (?,?,?, now() + interval '7 days') RETURNING id",
                Long.class,
                newerPublicPostId,
                "Public community poll?",
                1
        );
        jdbc.update("INSERT INTO poll_options(poll_id, text, sort_order) VALUES (?,?,?)", pollId, "Yes", 0);
        jdbc.update("INSERT INTO poll_options(poll_id, text, sort_order) VALUES (?,?,?)", pollId, "No", 1);
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility) " +
                        "VALUES (?,?,?,?,?,'quarantined')",
                authorId,
                authorPrincipalId,
                companyId,
                communityId,
                "Hidden post"
        );
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility, removed_at) " +
                        "VALUES (?,?,?,?,?,'public', now())",
                authorId,
                authorPrincipalId,
                companyId,
                communityId,
                "Removed post"
        );

        var firstPage = mockMvc.perform(get("/v1/public/communities/" + communityId + "/posts?limit=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", equalTo(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo((int) newerPublicPostId)))
                .andExpect(jsonPath("$.items[0].content", equalTo("Newer visible post")))
                .andExpect(jsonPath("$.items[0].poll.question", equalTo("Public community poll?")))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        JsonNode firstBody = objectMapper.readTree(firstPage.getResponse().getContentAsString());
        String nextCursor = firstBody.path("next_cursor").asText();

        mockMvc.perform(get("/v1/public/communities/" + communityId + "/posts?limit=1&cursor=" + nextCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", equalTo(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo((int) olderPublicPostId)))
                .andExpect(jsonPath("$.items[0].content", equalTo("Older visible post")));
    }

    @Test
    void public_community_posts_empty_list_when_no_public_posts() throws Exception {
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'No Posts Public Community') RETURNING id",
                Long.class
        );

        mockMvc.perform(get("/v1/public/communities/" + communityId + "/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", equalTo(0)))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void public_community_posts_returns_404_when_missing() throws Exception {
        mockMvc.perform(get("/v1/public/communities/999999/posts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", equalTo("community_not_found")))
                .andExpect(jsonPath("$.message", equalTo("Community not found")));
    }

    @Test
    void public_community_posts_returns_410_when_unavailable() throws Exception {
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('unknown', 'Unshareable Community Posts') RETURNING id",
                Long.class
        );

        mockMvc.perform(get("/v1/public/communities/" + communityId + "/posts"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error", equalTo("community_unavailable")))
                .andExpect(jsonPath("$.message", equalTo("Community is unavailable")));
    }

    @Test
    void public_community_posts_clamps_limit_and_handles_invalid_cursor() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Clamp Co', 'clamp.co') RETURNING id",
                Long.class
        );
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class,
                "uid-clamp-author",
                "clampauthor",
                companyId
        );
        long authorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                authorId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Clamp Community') RETURNING id",
                Long.class
        );

        long newestPostId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility, created_at) " +
                        "VALUES (?,?,?,?,?,'public', now()) RETURNING id",
                Long.class,
                authorId,
                authorPrincipalId,
                companyId,
                communityId,
                "Newest"
        );
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility, created_at) " +
                        "VALUES (?,?,?,?,?,'public', now() - interval '1 day')",
                authorId,
                authorPrincipalId,
                companyId,
                communityId,
                "Middle"
        );
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility, created_at) " +
                        "VALUES (?,?,?,?,?,'public', now() - interval '2 days')",
                authorId,
                authorPrincipalId,
                companyId,
                communityId,
                "Oldest"
        );

        mockMvc.perform(get("/v1/public/communities/" + communityId + "/posts?limit=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", equalTo(1)));

        mockMvc.perform(get("/v1/public/communities/" + communityId + "/posts?limit=500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", equalTo(3)));

        mockMvc.perform(get("/v1/public/communities/" + communityId + "/posts?limit=1&cursor=not-a-valid-cursor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", equalTo(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo((int) newestPostId)));
    }
}
