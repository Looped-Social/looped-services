package com.looped.widgets;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app",
        "cloudfront.domain=cdn.test.local"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class WidgetSummaryIntegrationTest extends PostgresTestBase {

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
    void widget_summary_returns_safe_defaults_when_no_data() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Widgets Co', 'widgets.co') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_completed_at) VALUES (?,?,?, now())",
                "uid-widget-empty",
                "widgetempty",
                companyId
        );

        mockMvc.perform(get("/v1/widget-summary")
                        .header("Authorization", "Bearer " + token("uid-widget-empty")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.server_time", notNullValue()))
                .andExpect(jsonPath("$.snapshot_ttl_seconds").value(900))
                .andExpect(jsonPath("$.inbox.unread_messages").value(0))
                .andExpect(jsonPath("$.inbox.message_requests").value(0))
                .andExpect(jsonPath("$.inbox.unread_mentions").value(0))
                .andExpect(jsonPath("$.profile_summary.display_name").value("widgetempty"))
                .andExpect(jsonPath("$.profile_summary.avatar_thumbnail_url").value(nullValue()))
                .andExpect(jsonPath("$.profile_summary.specialization").value(nullValue()))
                .andExpect(jsonPath("$.profile_summary.primary_community_name").value(nullValue()))
                .andExpect(jsonPath("$.profile_stats.followers").value(0))
                .andExpect(jsonPath("$.profile_stats.following").value(0))
                .andExpect(jsonPath("$.profile_stats.likes_received").value(0))
                .andExpect(jsonPath("$.recent_chats", hasSize(0)))
                .andExpect(jsonPath("$.verified_communities", hasSize(0)))
                .andExpect(jsonPath("$.trending_post").value(nullValue()));
    }

    @Test
    void widget_summary_includes_profile_summary() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Widgets Profile Co', 'widgets-profile.co') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name, profile_image_url, onboarding_completed_at) VALUES (?,?,?,?,?, now())",
                "uid-widget-profile",
                "widgetprofile",
                companyId,
                "Jane Doe",
                "https://cdn.test.local/media/original/avatar-thumb.jpg"
        );
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid = ?", Long.class, "uid-widget-profile");
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, short_name, member_count) VALUES ('company', 'Engineering', 'Eng', 125) RETURNING id",
                Long.class
        );
        long specializationId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, specialization_type) VALUES ('specialization', 'iOS Engineer', 'field') RETURNING id",
                Long.class
        );
        jdbc.update("UPDATE users SET display_community_id = ?, display_specialization_id = ? WHERE id = ?", communityId, specializationId, userId);
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?,?)",
                userId, communityId, "manual", true, now.minusHours(1)
        );

        mockMvc.perform(get("/v1/widget-summary")
                        .header("Authorization", "Bearer " + token("uid-widget-profile")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile_summary.display_name").value("Jane Doe"))
                .andExpect(jsonPath("$.profile_summary.avatar_thumbnail_url").value("https://cdn.test.local/media/original/avatar-thumb.jpg"))
                .andExpect(jsonPath("$.profile_summary.specialization").value("iOS Engineer"))
                .andExpect(jsonPath("$.profile_summary.primary_community_name").value("Engineering"));
    }

    @Test
    void widget_summary_unread_mentions_excludes_dismissed_notifications() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Widgets Dismiss Co', 'widgets-dismiss.co') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_completed_at) VALUES (?,?,?, now())",
                "uid-widget-dismissed-mentions",
                "widgetdismiss",
                companyId
        );
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid = ?", Long.class, "uid-widget-dismissed-mentions");

        jdbc.update(
                "INSERT INTO notifications(user_id, type, payload, created_at) VALUES (?, 'mention', ?::jsonb, now() - interval '2 minutes')",
                userId,
                "{}"
        );
        jdbc.update(
                "INSERT INTO notifications(user_id, type, payload, created_at, dismissed_at) VALUES (?, 'mention', ?::jsonb, now() - interval '1 minutes', now())",
                userId,
                "{}"
        );
        jdbc.update(
                "INSERT INTO notifications(user_id, type, payload, created_at, read_at) VALUES (?, 'mention', ?::jsonb, now(), now())",
                userId,
                "{}"
        );

        mockMvc.perform(get("/v1/widget-summary")
                        .header("Authorization", "Bearer " + token("uid-widget-dismissed-mentions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inbox.unread_mentions").value(1));
    }

    @Test
    void widget_summary_aggregates_counts_and_mark_seen_updates_activity() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();

        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Widgets Aggregation Co', 'widgets-agg.co') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_completed_at) VALUES (?,?,?, now())",
                "uid-widget-agg",
                "widgetagg",
                companyId
        );
        long actorUserId = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid = ?", Long.class, "uid-widget-agg");
        long actorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                actorUserId
        );

        jdbc.update(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_completed_at) VALUES (?,?,?, now())",
                "uid-widget-other",
                "widgetother",
                companyId
        );
        long otherUserId = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid = ?", Long.class, "uid-widget-other");
        long otherPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                otherUserId
        );

        jdbc.update(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_completed_at) VALUES (?,?,?, now())",
                "uid-widget-requester",
                "widgetrequester",
                companyId
        );
        long requesterUserId = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid = ?", Long.class, "uid-widget-requester");
        long requesterPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                requesterUserId
        );

        long communityOneId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, short_name, member_count) VALUES ('company', 'Engineering', 'Eng', 612) RETURNING id",
                Long.class
        );
        long communityTwoId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, short_name, member_count) VALUES ('company', 'Design', 'Des', 420) RETURNING id",
                Long.class
        );
        jdbc.update("UPDATE users SET display_community_id = ? WHERE id = ?", communityOneId, actorUserId);
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?,?)",
                actorUserId, communityOneId, "manual", true, now.minusHours(5)
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?,?)",
                actorUserId, communityTwoId, "manual", true, now.minusHours(5)
        );
        jdbc.update(
                "INSERT INTO community_follows(user_id, community_id, created_at) VALUES (?,?,?)",
                actorUserId, communityOneId, now.minusDays(1)
        );
        jdbc.update(
                "INSERT INTO community_follows(user_id, community_id, created_at) VALUES (?,?,?)",
                actorUserId, communityTwoId, now.minusHours(4)
        );
        jdbc.update(
                "INSERT INTO widget_community_state(user_id, community_id, last_seen_at) VALUES (?,?,?)",
                actorUserId, communityOneId, now.minusMinutes(90)
        );

        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, likes_count, visibility, created_at) " +
                        "VALUES (?,?,?,?,?,?, 'public', ?)",
                actorUserId, actorPrincipalId, companyId, communityOneId, "old liked post", 5, now.minusDays(3)
        );
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, likes_count, visibility, created_at) " +
                        "VALUES (?,?,?,?,?,?, 'public', ?)",
                actorUserId, actorPrincipalId, companyId, communityOneId, "another old liked post", 3, now.minusDays(2)
        );

        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility, created_at) " +
                        "VALUES (?,?,?,?,?, 'public', ?)",
                otherUserId, otherPrincipalId, companyId, communityOneId, "old activity one", now.minusHours(2)
        );
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility, created_at) " +
                        "VALUES (?,?,?,?,?, 'public', ?)",
                otherUserId, otherPrincipalId, companyId, communityOneId, "new activity one", now.minusMinutes(30)
        );
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility, created_at, removed_at) " +
                        "VALUES (?,?,?,?,?, 'public', ?, ?)",
                otherUserId, otherPrincipalId, companyId, communityOneId, "removed activity", now.minusMinutes(5), now.minusMinutes(1)
        );
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility, created_at) " +
                        "VALUES (?,?,?,?,?, 'public', ?)",
                otherUserId, otherPrincipalId, companyId, communityTwoId, "old activity two", now.minusHours(6)
        );
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, visibility, created_at) " +
                        "VALUES (?,?,?,?,?, 'public', ?)",
                otherUserId, otherPrincipalId, companyId, communityTwoId, "new activity two", now.minusHours(2)
        );

        long conversationId = jdbc.queryForObject(
                "INSERT INTO conversations(company_id) VALUES (?) RETURNING id",
                Long.class,
                companyId
        );
        jdbc.update(
                "INSERT INTO conversation_participants(conversation_id, user_id, last_read_at) VALUES (?,?,?)",
                conversationId,
                actorUserId,
                now.minusHours(2)
        );
        jdbc.update(
                "INSERT INTO conversation_participants(conversation_id, user_id, last_read_at) VALUES (?,?,?)",
                conversationId,
                otherUserId,
                now
        );
        jdbc.update(
                "INSERT INTO conversation_messages(conversation_id, sender_id, content, attachments, created_at) VALUES (?,?,?, ?::jsonb, ?)",
                conversationId, otherUserId, "older read message", "[]", now.minusHours(3)
        );
        jdbc.update(
                "INSERT INTO conversation_messages(conversation_id, sender_id, content, attachments, created_at) VALUES (?,?,?, ?::jsonb, ?)",
                conversationId, otherUserId, "unread one", "[]", now.minusMinutes(70)
        );
        jdbc.update(
                "INSERT INTO conversation_messages(conversation_id, sender_id, content, attachments, created_at) VALUES (?,?,?, ?::jsonb, ?)",
                conversationId, otherUserId, "unread two", "[]", now.minusMinutes(20)
        );

        long requestConversationId = jdbc.queryForObject(
                "INSERT INTO conversations(company_id) VALUES (?) RETURNING id",
                Long.class,
                companyId
        );
        jdbc.update(
                "INSERT INTO conversation_participants(conversation_id, user_id, last_read_at) VALUES (?,?,?)",
                requestConversationId, actorUserId, now.minusDays(1)
        );
        jdbc.update(
                "INSERT INTO conversation_participants(conversation_id, user_id, last_read_at) VALUES (?,?,?)",
                requestConversationId, requesterUserId, now
        );
        long requestMessageId = jdbc.queryForObject(
                "INSERT INTO conversation_messages(conversation_id, sender_id, content, attachments, created_at) VALUES (?,?,?, ?::jsonb, ?) RETURNING id",
                Long.class,
                requestConversationId, requesterUserId, "pending request message", "[]", now.minusMinutes(10)
        );
        jdbc.update(
                "INSERT INTO conversation_message_requests(conversation_id, requester_id, recipient_id, message_id, status, created_at, updated_at) " +
                        "VALUES (?,?,?,?, 'pending', ?, ?)",
                requestConversationId, requesterUserId, actorUserId, requestMessageId, now.minusMinutes(10), now.minusMinutes(10)
        );

        jdbc.update(
                "INSERT INTO notifications(user_id, type, payload, created_at) VALUES (?, 'mention', ?::jsonb, ?)",
                actorUserId, "{}", now.minusMinutes(15)
        );
        jdbc.update(
                "INSERT INTO notifications(user_id, type, payload, created_at, read_at) VALUES (?, 'mention', ?::jsonb, ?, ?)",
                actorUserId, "{}", now.minusMinutes(20), now.minusMinutes(5)
        );
        jdbc.update(
                "INSERT INTO notifications(user_id, type, payload, created_at) VALUES (?, 'like', ?::jsonb, ?)",
                actorUserId, "{}", now.minusMinutes(12)
        );

        jdbc.update(
                "INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?, ?)",
                otherPrincipalId, actorPrincipalId
        );
        jdbc.update(
                "INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?, ?)",
                actorPrincipalId, requesterPrincipalId
        );

        String auth = "Bearer " + token("uid-widget-agg");

        mockMvc.perform(get("/v1/widget-summary").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inbox.unread_messages").value(2))
                .andExpect(jsonPath("$.inbox.message_requests").value(1))
                .andExpect(jsonPath("$.inbox.unread_mentions").value(1))
                .andExpect(jsonPath("$.profile_summary.display_name").value("widgetagg"))
                .andExpect(jsonPath("$.profile_summary.primary_community_name").value("Engineering"))
                .andExpect(jsonPath("$.profile_summary.specialization").value(nullValue()))
                .andExpect(jsonPath("$.profile_summary.avatar_thumbnail_url").value(nullValue()))
                .andExpect(jsonPath("$.profile_stats.followers").value(1))
                .andExpect(jsonPath("$.profile_stats.following").value(1))
                .andExpect(jsonPath("$.profile_stats.likes_received").value(8))
                .andExpect(jsonPath("$.recent_chats", hasSize(1)))
                .andExpect(jsonPath("$.recent_chats[0].conversation_id").value(conversationId))
                .andExpect(jsonPath("$.recent_chats[0].title").value("widgetother"))
                .andExpect(jsonPath("$.recent_chats[0].avatar_thumbnail_url").value(nullValue()))
                .andExpect(jsonPath("$.recent_chats[0].last_message_preview").value("unread two"))
                .andExpect(jsonPath("$.recent_chats[0].unread_count").value(2))
                .andExpect(jsonPath("$.default_community_id").value(communityOneId))
                .andExpect(jsonPath("$.verified_communities", hasSize(2)))
                .andExpect(jsonPath("$.verified_communities[?(@.id==" + communityOneId + ")].new_activity_count").value(contains(1)))
                .andExpect(jsonPath("$.verified_communities[?(@.id==" + communityTwoId + ")].new_activity_count").value(contains(1)));

        mockMvc.perform(post("/v1/widget-state/community/" + communityTwoId + "/seen")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.community_id").value(communityTwoId))
                .andExpect(jsonPath("$.seen_at", notNullValue()));

        mockMvc.perform(get("/v1/widget-summary").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified_communities[?(@.id==" + communityOneId + ")].new_activity_count").value(contains(1)))
                .andExpect(jsonPath("$.verified_communities[?(@.id==" + communityTwoId + ")].new_activity_count").value(contains(0)));
    }

    @Test
    void widget_summary_includes_trending_post_when_available() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Widgets Trending Co', 'widgets-trending.co') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_completed_at) VALUES (?,?,?, now())",
                "uid-widget-trending",
                "widgettrending",
                companyId
        );
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid = ?", Long.class, "uid-widget-trending");
        long principalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                userId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Engineering') RETURNING id",
                Long.class
        );
        long mediaId = jdbc.queryForObject(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type) VALUES (?,?,?) RETURNING id",
                Long.class,
                userId,
                "media/original/widget-trending-image.jpg",
                "image/jpeg"
        );
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content, media_asset_id, likes_count, comments_count, share_count, visibility, created_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?, 'public', ?) RETURNING id",
                Long.class,
                userId, principalId, companyId, communityId,
                "   Widget   trending   preview   post   ", mediaId, 44, 12, 3, now.minusMinutes(20)
        );

        mockMvc.perform(get("/v1/widget-summary")
                        .header("Authorization", "Bearer " + token("uid-widget-trending")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trending_post.post_id").value(postId))
                .andExpect(jsonPath("$.trending_post.community_name").value("Engineering"))
                .andExpect(jsonPath("$.trending_post.content_preview").value("Widget trending preview post"))
                .andExpect(jsonPath("$.trending_post.like_count").value(44))
                .andExpect(jsonPath("$.trending_post.comment_count").value(12))
                .andExpect(jsonPath("$.trending_post.media_thumbnail_url")
                        .value("https://cdn.test.local/media/original/widget-trending-image.jpg"));
    }

    @Test
    void mark_seen_requires_active_verification() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('Widgets Seen Co', 'widgets-seen.co') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_completed_at) VALUES (?,?,?, now())",
                "uid-widget-seen",
                "widgetseen",
                companyId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Unverified Community') RETURNING id",
                Long.class
        );

        mockMvc.perform(post("/v1/widget-state/community/" + communityId + "/seen")
                        .header("Authorization", "Bearer " + token("uid-widget-seen")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("community_not_verified"));
    }
}
