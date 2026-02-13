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
}
