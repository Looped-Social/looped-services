package com.looped.integration;

import com.looped.auth.TestSecurityConfig;
import com.looped.notifications.NotificationPublisher;
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

import static org.hamcrest.Matchers.*;
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
class ExtendedEndpointsIntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NotificationPublisher notificationPublisher;

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
    void profile_update_alias_and_search_and_comments() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('Acme','acme.com') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-profile", "acmeuser", companyId, "Old");
        long followerId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-follower", "sidekick", companyId);
        long userPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, userId);
        long followerPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, followerId);
        jdbc.update("INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)", followerPrincipal, userPrincipal);
        long post1 = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, userId, userPrincipal, companyId, "first");
        long post2 = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, userId, userPrincipal, companyId, "second");
        long commentId = jdbc.queryForObject("INSERT INTO comments(post_id, user_id, author_principal_id, company_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, post1, userId, userPrincipal, companyId, "first-comment");
        jdbc.update("UPDATE posts SET likes_count = 3 WHERE id = ?", post1);
        jdbc.update("UPDATE posts SET likes_count = 4 WHERE id = ?", post2);
        jdbc.update("UPDATE comments SET likes_count = 2 WHERE id = ?", commentId);

        String auth = "Bearer " + token("uid-profile");

        mockMvc.perform(put("/v1/users/me")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"New Name\",\"bio\":\"Updated bio\",\"isAnonymous\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("New Name"))
                .andExpect(jsonPath("$.stats.posts_count").value(2))
                .andExpect(jsonPath("$.stats.follower_count").value(1))
                .andExpect(jsonPath("$.stats.comments_count").value(1))
                .andExpect(jsonPath("$.stats.likes_received_count").value(9));

        mockMvc.perform(put("/users/me")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Alias Path\",\"bio\":\"Alias\",\"isAnonymous\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_anonymous").value(true));

        mockMvc.perform(get("/v1/users/search?query=acme")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].handle", equalTo("acmeuser")))
                .andExpect(jsonPath("$.items[0].display_name", equalTo("Alias Path")));

        mockMvc.perform(get("/v1/users/" + userId + "/comments")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].content", equalTo("first-comment")));
    }

    @Test
    void user_search_and_directory_exclude_blocked_relationships_and_restore_on_unblock() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('SearchBlockCo','searchblock.co') RETURNING id", Long.class);
        jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name, onboarding_step, onboarding_completed_at) " +
                        "VALUES (?,?,?,?,'verification_notifications', now()) RETURNING id",
                Long.class, "uid-search-block-actor", "searchblockactor", companyId, "Search Actor");
        long blockedUserId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name, onboarding_step, onboarding_completed_at) " +
                        "VALUES (?,?,?,?,'verification_notifications', now()) RETURNING id",
                Long.class, "uid-search-block-target", "searchblocktarget", companyId, "Search Target");
        jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name, onboarding_step, onboarding_completed_at) " +
                        "VALUES (?,?,?,?,'verification_notifications', now()) RETURNING id",
                Long.class, "uid-search-block-other", "searchblockother", companyId, "Search Other");

        String auth = "Bearer " + token("uid-search-block-actor");

        mockMvc.perform(get("/v1/users/search?query=searchblocktarget")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].handle", hasItem("searchblocktarget")));

        mockMvc.perform(get("/v1/users")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].handle", hasItem("searchblocktarget")));

        mockMvc.perform(post("/v1/users/" + blockedUserId + "/block")
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blocked", equalTo(true)));

        mockMvc.perform(get("/v1/users/search?query=searchblocktarget")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].handle", not(hasItem("searchblocktarget"))));

        mockMvc.perform(get("/v1/users")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].handle", not(hasItem("searchblocktarget"))));

        mockMvc.perform(delete("/v1/users/" + blockedUserId + "/block")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked", equalTo(false)));

        mockMvc.perform(get("/v1/users/search?query=searchblocktarget")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].handle", hasItem("searchblocktarget")));

        mockMvc.perform(get("/v1/users")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].handle", hasItem("searchblocktarget")));
    }

    @Test
    void conversations_list_filters_out_invalid_conversations_missing_other_participant() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('MsgCo','msg.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-msg-user", "msguser", companyId);

        long conversationId = jdbc.queryForObject("INSERT INTO conversations(company_id) VALUES (?) RETURNING id", Long.class, companyId);
        jdbc.update("INSERT INTO conversation_participants(conversation_id, user_id, last_read_at) VALUES (?,?, now())",
                conversationId, userId);

        String auth = "Bearer " + token("uid-msg-user");

        mockMvc.perform(get("/v1/conversations")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void conversations_preferences_mute_roundtrip() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('MuteCo','mute.co') RETURNING id", Long.class);
        long userA = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-mute-a", "mutea", companyId);
        long userB = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-mute-b", "muteb", companyId);

        String authA = "Bearer " + token("uid-mute-a");

        var startResp = mockMvc.perform(post("/v1/conversations")
                        .header("Authorization", authA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantUserId\":" + userB + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        String conversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(startResp.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(put("/v1/conversations/" + conversationId + "/preferences")
                        .header("Authorization", authA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"muted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.muted", equalTo(true)));

        mockMvc.perform(get("/v1/conversations")
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].muted", equalTo(true)));

        mockMvc.perform(put("/v1/conversations/" + conversationId + "/preferences")
                        .header("Authorization", authA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"muted\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.muted", equalTo(false)));

        mockMvc.perform(get("/v1/conversations")
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].muted", equalTo(false)));
    }

    @Test
    void blocked_users_do_not_generate_notifications() {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('BlockCo','block.co') RETURNING id", Long.class);
        long targetUserId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-block-target", "target", companyId);
        long actorUserId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-block-actor", "actor", companyId);

        long targetPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, targetUserId);
        long actorPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, actorUserId);

        // Target blocks actor.
        jdbc.update("INSERT INTO principal_blocks(blocker_principal_id, blocked_principal_id) VALUES (?,?)",
                targetPrincipalId, actorPrincipalId);

        notificationPublisher.notifyFollow(targetUserId, actorPrincipalId);

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Integer.class, targetUserId);
        org.junit.jupiter.api.Assertions.assertEquals(0, count);
    }

    @Test
    void conversation_flow_and_messages() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('ChatCo','chat.co') RETURNING id", Long.class);
        long actorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-chat-a", "alpha", companyId);
        long targetId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-chat-b", "bravo", companyId);

        String auth = "Bearer " + token("uid-chat-a");

        var startResp = mockMvc.perform(post("/v1/conversations")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantUserId\":" + targetId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.other_user_id", equalTo((int) targetId)))
                .andReturn();

        String conversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(startResp.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\",\"attachments\":[{\"url\":\"dm/original/abc\",\"type\":\"image\",\"width\":1,\"height\":2,\"sizeBytes\":3}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("hello"))
                .andExpect(jsonPath("$.attachments[0].url", equalTo("dm/original/abc")))
                .andExpect(jsonPath("$.attachments[0].type", equalTo("image")))
                .andExpect(jsonPath("$.attachments[0].width", equalTo(1)))
                .andExpect(jsonPath("$.attachments[0].height", equalTo(2)))
                .andExpect(jsonPath("$.attachments[0].size_bytes", equalTo(3)));

        mockMvc.perform(get("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sender_id", equalTo((int) actorId)))
                .andExpect(jsonPath("$.items[0].content", equalTo("hello")))
                .andExpect(jsonPath("$.items[0].attachments[0].url", equalTo("dm/original/abc")));

        mockMvc.perform(get("/v1/conversations")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].last_message", equalTo("hello")))
                .andExpect(jsonPath("$.items[0].other_user_profile.handle", equalTo("bravo")));
    }

    @Test
    void blocked_relationship_hides_conversations_and_search_rejects_start_and_send_until_unblocked() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('DmBlockCo','dmblock.co') RETURNING id", Long.class);
        long userA = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name, onboarding_step, onboarding_completed_at) " +
                        "VALUES (?,?,?,?,'verification_notifications', now()) RETURNING id",
                Long.class, "uid-dm-block-a", "dmblocka", companyId, "DM Block A");
        long userB = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name, onboarding_step, onboarding_completed_at) " +
                        "VALUES (?,?,?,?,'verification_notifications', now()) RETURNING id",
                Long.class, "uid-dm-block-b", "dmblockb", companyId, "DM Block B");
        long principalA = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, userA);
        long principalB = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, userB);
        jdbc.update("INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)", principalB, principalA);

        String authA = "Bearer " + token("uid-dm-block-a");
        String authB = "Bearer " + token("uid-dm-block-b");

        var startResp = mockMvc.perform(post("/v1/conversations")
                        .header("Authorization", authA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantUserId\":" + userB + "}"))
                .andExpect(status().isCreated())
                .andReturn();

        String conversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(startResp.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", authA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello block path\",\"attachments\":[]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/conversations")
                        .header("Authorization", authB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));

        mockMvc.perform(get("/v1/messages/search?query=hello%20block")
                        .header("Authorization", authB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.type=='conversation')].other_user_profile.handle", hasItem("dmblocka")));

        mockMvc.perform(post("/v1/users/" + userB + "/block")
                        .header("Authorization", authA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blocked", equalTo(true)));

        mockMvc.perform(post("/v1/conversations")
                        .header("Authorization", authA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantUserId\":" + userB + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("blocked_relationship")));

        mockMvc.perform(post("/v1/conversations")
                        .header("Authorization", authB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantUserId\":" + userA + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("blocked_relationship")));

        mockMvc.perform(get("/v1/conversations")
                        .header("Authorization", authB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        mockMvc.perform(get("/v1/messages/search?query=hello%20block")
                        .header("Authorization", authB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.type=='conversation')]", hasSize(0)));

        mockMvc.perform(post("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", authA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"should fail while blocked\",\"attachments\":[]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("blocked_relationship")));

        mockMvc.perform(get("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", authB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("blocked_relationship")));

        mockMvc.perform(delete("/v1/users/" + userB + "/block")
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked", equalTo(false)));

        mockMvc.perform(get("/v1/conversations")
                        .header("Authorization", authB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));

        mockMvc.perform(post("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", authA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"works after unblock\",\"attachments\":[]}"))
                .andExpect(status().isCreated());
    }

    @Test
    void start_conversation_is_idempotent_for_same_pair() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('IdemCo','idem.co') RETURNING id", Long.class);
        long senderId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-idem-a", "idem-a", companyId);
        long recipientId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-idem-b", "idem-b", companyId);

        String senderAuth = "Bearer " + token("uid-idem-a");

        var firstStartResp = mockMvc.perform(post("/v1/conversations")
                        .header("Authorization", senderAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantUserId\":" + recipientId + "}"))
                .andExpect(status().isCreated())
                .andReturn();

        long firstConversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(firstStartResp.getResponse().getContentAsString())
                .get("id").asLong();

        var secondStartResp = mockMvc.perform(post("/v1/conversations")
                        .header("Authorization", senderAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantUserId\":" + recipientId + "}"))
                .andExpect(status().isCreated())
                .andReturn();

        long secondConversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(secondStartResp.getResponse().getContentAsString())
                .get("id").asLong();

        assertEquals(firstConversationId, secondConversationId);
        Integer conversationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversations",
                Integer.class
        );
        Integer participantCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_participants WHERE conversation_id = ?",
                Integer.class,
                firstConversationId
        );
        assertEquals(1, conversationCount);
        assertEquals(2, participantCount);
    }

    @Test
    void cannot_start_conversation_with_self() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('SelfCo','self.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-self", "self", companyId);

        String auth = "Bearer " + token("uid-self");

        mockMvc.perform(post("/v1/conversations")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantUserId\":" + userId + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", equalTo("invalid_participant")));
    }

    @Test
    void message_requests_flow() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('ReqCo','req.co') RETURNING id", Long.class);
        long senderId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-req-a", "sender", companyId);
        long recipientId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-req-b", "recipient", companyId);

        String senderAuth = "Bearer " + token("uid-req-a");
        String recipientAuth = "Bearer " + token("uid-req-b");

        var startResp = mockMvc.perform(post("/v1/conversations")
                        .header("Authorization", senderAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantUserId\":" + recipientId + "}"))
                .andExpect(status().isCreated())
                .andReturn();

        String conversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(startResp.getResponse().getContentAsString())
                .get("id").asText();
        long conversationIdLong = Long.parseLong(conversationId);

        mockMvc.perform(post("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", senderAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"request hello\",\"attachments\":[]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/notifications")
                        .header("Authorization", recipientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].type", equalTo("message_request")))
                .andExpect(jsonPath("$.items[0].payload.conversation_id", equalTo(Integer.parseInt(conversationId))))
                .andExpect(jsonPath("$.items[0].payload.deeplink", equalTo("looped://conversations/" + conversationId)))
                .andExpect(jsonPath("$.items[0].payload.action_deeplink", equalTo("looped://conversations/" + conversationId)));

        mockMvc.perform(post("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", senderAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"request hello 2\",\"attachments\":[]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/notifications")
                        .header("Authorization", recipientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));

        mockMvc.perform(get("/v1/conversations")
                        .header("Authorization", recipientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        var requestResp = mockMvc.perform(get("/v1/message-requests")
                        .header("Authorization", recipientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].requester_id", equalTo((int) senderId)))
                .andExpect(jsonPath("$.items[0].message.content", equalTo("request hello 2")))
                .andReturn();

        String requestId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(requestResp.getResponse().getContentAsString())
                .get("items").get(0).get("id").asText();

        mockMvc.perform(get("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", recipientAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("message_request_pending")));

        mockMvc.perform(post("/v1/message-requests/" + requestId + "/approve")
                        .header("Authorization", recipientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("approved")));

        mockMvc.perform(get("/v1/conversations")
                        .header("Authorization", recipientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].last_message", equalTo("request hello 2")));

        mockMvc.perform(get("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", recipientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].content", containsInAnyOrder("request hello", "request hello 2")));

        mockMvc.perform(post("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", recipientAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"approved reply\",\"attachments\":[]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/message-requests")
                        .header("Authorization", senderAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        mockMvc.perform(post("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", senderAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"reply back\",\"attachments\":[]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", senderAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].content", hasItems("approved reply", "reply back")));

        Integer reversePendingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_message_requests WHERE conversation_id = ? AND recipient_id = ? AND status = 'pending'",
                Integer.class,
                conversationIdLong,
                senderId
        );
        assertEquals(0, reversePendingCount);
    }

    @Test
    void blocked_relationship_hides_message_requests_until_unblock() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('ReqBlockCo','reqblock.co') RETURNING id", Long.class);
        long senderId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_step, onboarding_completed_at) " +
                        "VALUES (?, ?, ?, 'verification_notifications', now()) RETURNING id",
                Long.class, "uid-req-block-sender", "reqblocksender", companyId);
        long recipientId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_step, onboarding_completed_at) " +
                        "VALUES (?, ?, ?, 'verification_notifications', now()) RETURNING id",
                Long.class, "uid-req-block-recipient", "reqblockrecipient", companyId);

        String senderAuth = "Bearer " + token("uid-req-block-sender");
        String recipientAuth = "Bearer " + token("uid-req-block-recipient");

        var startResp = mockMvc.perform(post("/v1/conversations")
                        .header("Authorization", senderAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantUserId\":" + recipientId + "}"))
                .andExpect(status().isCreated())
                .andReturn();

        String conversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(startResp.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", senderAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"pending request\",\"attachments\":[]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/message-requests")
                        .header("Authorization", recipientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));

        mockMvc.perform(post("/v1/users/" + recipientId + "/block")
                        .header("Authorization", senderAuth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blocked", equalTo(true)));

        mockMvc.perform(get("/v1/message-requests")
                        .header("Authorization", recipientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        mockMvc.perform(delete("/v1/users/" + recipientId + "/block")
                        .header("Authorization", senderAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked", equalTo(false)));

        mockMvc.perform(get("/v1/message-requests")
                        .header("Authorization", recipientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void anonymous_users_cannot_message() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('AnonCo','anon.co') RETURNING id", Long.class);
        long anonId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, is_anonymous) VALUES (?,?,?, true) RETURNING id",
                Long.class, "uid-anon-msg", "anonmsg", companyId);
        long targetId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-anon-target", "target", companyId);

        String auth = "Bearer " + token("uid-anon-msg");

        mockMvc.perform(post("/v1/conversations")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantUserId\":" + targetId + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("anonymous_not_allowed")));
    }

    @Test
    void message_search_matches_people_and_content() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('SearchCo','search.co') RETURNING id", Long.class);
        jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-search", "searcher", companyId);
        long adamId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-adam", "adamsandler", companyId, "Adam Sandler");
        long ericId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-eric", "eric", companyId, "Eric");

        String auth = "Bearer " + token("uid-search");

        var startAdamResp = mockMvc.perform(post("/v1/conversations")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantUserId\":" + adamId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        String adamConversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(startAdamResp.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/v1/conversations/" + adamConversationId + "/messages")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\",\"attachments\":[]}"))
                .andExpect(status().isCreated());

        var startEricResp = mockMvc.perform(post("/v1/conversations")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantUserId\":" + ericId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        String ericConversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(startEricResp.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/v1/conversations/" + ericConversationId + "/messages")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"we talked about ice cream\",\"attachments\":[]}"))
                .andExpect(status().isCreated());

        var chanResp = mockMvc.perform(post("/v1/channels")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ice Cream Club\",\"memberUserIds\":[]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String channelId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(chanResp.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/v1/channels/" + channelId + "/messages")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"ice cream with sprinkles\",\"attachments\":[]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/messages/search?query=adam")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", not(empty())))
                .andExpect(jsonPath("$.items[0].type", equalTo("conversation")))
                .andExpect(jsonPath("$.items[0].other_user_profile.handle", equalTo("adamsandler")));

        mockMvc.perform(get("/v1/messages/search?query=ice%20cream")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].type", hasItems("conversation", "channel")))
                .andExpect(jsonPath("$.items[?(@.type=='conversation')].other_user_profile.handle", hasItem("eric")));
    }

    @Test
    void channel_owner_can_delete_channel() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('ChanCo','chan.co') RETURNING id", Long.class);
        jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-chan-owner", "owner", companyId);
        long memberId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-chan-member", "member", companyId);

        String ownerAuth = "Bearer " + token("uid-chan-owner");
        String memberAuth = "Bearer " + token("uid-chan-member");

        var chanResp = mockMvc.perform(post("/v1/channels")
                        .header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Delete Me\",\"memberUserIds\":[" + memberId + "]}"))
                .andExpect(status().isCreated())
                .andReturn();
        long channelId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(chanResp.getResponse().getContentAsString())
                .get("id").asLong();

        mockMvc.perform(post("/v1/channels/" + channelId + "/messages")
                        .header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\",\"attachments\":[]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/v1/channels/" + channelId)
                        .header("Authorization", memberAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("forbidden")));

        mockMvc.perform(delete("/v1/channels/" + channelId)
                        .header("Authorization", ownerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("ok")));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM channels WHERE id = ?", Integer.class, channelId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM channel_members WHERE channel_id = ?", Integer.class, channelId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM channel_messages WHERE channel_id = ?", Integer.class, channelId));

        mockMvc.perform(get("/v1/channels/" + channelId + "/messages")
                        .header("Authorization", ownerAuth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", equalTo("not_found")));
    }

    @Test
    void discovery_search_is_relevant() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('DiscCo','disc.co') RETURNING id", Long.class);
        jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-disc", "discoverer", companyId);

        jdbc.update("INSERT INTO communities(kind, name, description, member_count) VALUES ('company','Ice Cream Club','Best place for frozen treats', 1000)");
        jdbc.update("INSERT INTO communities(kind, name, description, member_count) VALUES ('company','Eric Group','We once talked about ice cream', 10)");

        jdbc.update("INSERT INTO hashtags(company_id, name, usage_count) VALUES (?,?,?)", companyId, "icecream", 500);
        jdbc.update("INSERT INTO hashtags(company_id, name, usage_count) VALUES (?,?,?)", companyId, "inception", 5);

        String auth = "Bearer " + token("uid-disc");

        mockMvc.perform(get("/v1/communities/search?query=ice%20cream")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name", equalTo("Ice Cream Club")));

        mockMvc.perform(get("/v1/hashtags/search?query=ice")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name", equalTo("icecream")));
    }

    @Test
    void violations_list_for_removed_posts_and_active_bans() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('ViolCo','viol.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-viol", "violator", companyId);
        long principalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, userId);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, content, removed_at, removed_reason) " +
                        "VALUES (?,?,?,?, now(), ?) RETURNING id",
                Long.class, userId, principalId, companyId, "removed post", "policy violation");
        long banId = jdbc.queryForObject("INSERT INTO user_bans(user_id, reason) VALUES (?,?) RETURNING id",
                Long.class, userId, "ban reason");

        String auth = "Bearer " + token("uid-viol");

        mockMvc.perform(get("/v1/violations")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].target_type", hasItems("post_removal", "user_ban")))
                .andExpect(jsonPath("$.items[*].target_id", hasItems((int) postId, (int) banId)))
                .andExpect(jsonPath("$.items[*].reason", hasItems("policy violation", "ban reason")))
                .andExpect(jsonPath("$.items[*].status", hasItems("removed", "active")));
    }

    @Test
    void channels_and_notifications_and_search_directory() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('ChannelCo','ch.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-channel", "charlie", companyId);
        long chanId = jdbc.queryForObject("INSERT INTO channels(company_id, owner_user_id, name, is_public) VALUES (?,?,?, true) RETURNING id",
                Long.class, companyId, userId, "general");
        long photoId = jdbc.queryForObject(
                "INSERT INTO media_assets(owner_id, s3_key, mime_type, width, height) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, userId, "media/original/photo-" + chanId, "image/jpeg", 1, 1
        );

        String auth = "Bearer " + token("uid-channel");

        mockMvc.perform(get("/v1/channels")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name", equalTo("general")))
                .andExpect(jsonPath("$.items[0].member_count", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.items[0].muted", equalTo(false)));

        mockMvc.perform(post("/v1/channels/" + chanId + "/join")
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.joined", equalTo(true)));

        mockMvc.perform(patch("/v1/channels/" + chanId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"general-2\",\"photoMediaAssetId\":" + photoId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", equalTo("general-2")))
                .andExpect(jsonPath("$.photo_media_asset_id", equalTo((int) photoId)));

        mockMvc.perform(put("/v1/channels/" + chanId + "/preferences")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"muted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.muted", equalTo(true)));

        jdbc.update("INSERT INTO notifications(user_id, type, payload) VALUES (?,?, ?::jsonb)", userId, "channel.mention",
                "{\"channel_id\":" + chanId + "}");

        mockMvc.perform(get("/v1/notifications")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        mockMvc.perform(put("/v1/channels/" + chanId + "/preferences")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"muted\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.muted", equalTo(false)));

        mockMvc.perform(post("/v1/channels/" + chanId + "/messages")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi channel\",\"attachments\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("hi channel"));

        mockMvc.perform(get("/v1/channels/" + chanId + "/messages")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].content").value("hi channel"));

        var notifResp = mockMvc.perform(get("/v1/notifications")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unread").value(true))
                .andReturn();

        String notifId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(notifResp.getResponse().getContentAsString())
                .get("items").get(0).get("id").asText();

        mockMvc.perform(post("/v1/notifications/" + notifId + "/read")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
    }

    @Test
    void notifications_include_action_deeplink_when_deeplink_present() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('NotifCo','notif.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-notif", "notifier", companyId);
        jdbc.update("INSERT INTO notifications(user_id, type, payload) VALUES (?,?, ?::jsonb)", userId, "system",
                "{\"deeplink\":\"looped://post/123\"}");

        String auth = "Bearer " + token("uid-notif");

        mockMvc.perform(get("/v1/notifications")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].payload.deeplink", equalTo("looped://post/123")))
                .andExpect(jsonPath("$.items[0].payload.action_deeplink", equalTo("looped://post/123")));
    }

    @Test
    void notifications_support_single_dismiss_and_dismiss_all() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('NotifDismissCo','notifdismiss.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-notif-dismiss", "notifierdismiss", companyId);
        long otherUserId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-notif-dismiss-other", "notifierdismissother", companyId);

        long firstId = jdbc.queryForObject(
                "INSERT INTO notifications(user_id, type, payload, created_at) VALUES (?,?, ?::jsonb, now() - interval '3 minutes') RETURNING id",
                Long.class,
                userId,
                "mention",
                "{}"
        );
        long secondId = jdbc.queryForObject(
                "INSERT INTO notifications(user_id, type, payload, created_at) VALUES (?,?, ?::jsonb, now() - interval '2 minutes') RETURNING id",
                Long.class,
                userId,
                "like",
                "{}"
        );
        jdbc.queryForObject(
                "INSERT INTO notifications(user_id, type, payload, created_at) VALUES (?,?, ?::jsonb, now() - interval '1 minutes') RETURNING id",
                Long.class,
                userId,
                "comment",
                "{}"
        );
        long otherUserNotificationId = jdbc.queryForObject(
                "INSERT INTO notifications(user_id, type, payload) VALUES (?,?, ?::jsonb) RETURNING id",
                Long.class,
                otherUserId,
                "mention",
                "{}"
        );

        String auth = "Bearer " + token("uid-notif-dismiss");

        mockMvc.perform(get("/v1/notifications")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)));

        mockMvc.perform(post("/v1/notifications/" + secondId + "/dismiss")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed", equalTo(true)));

        mockMvc.perform(get("/v1/notifications")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].id", not(hasItem((int) secondId))));

        mockMvc.perform(get("/v1/notifications?includeDismissed=true")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[?(@.id==" + secondId + ")].unread", contains(false)));

        mockMvc.perform(post("/v1/notifications/" + otherUserNotificationId + "/dismiss")
                        .header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", equalTo("not_found")));

        mockMvc.perform(post("/v1/notifications/dismiss-all")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissedCount", equalTo(2)));

        mockMvc.perform(get("/v1/notifications")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        mockMvc.perform(post("/v1/notifications/dismiss-all")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissedCount", equalTo(0)));

        mockMvc.perform(post("/v1/notifications/" + firstId + "/dismiss")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed", equalTo(true)));
    }

    @Test
    void me_analytics_returns_total_and_window_counts() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('AnalyticCo','ana.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-ana", "analyst", companyId);
        long userPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, userId);

        long likerA = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-like-a", "likerA", companyId);
        long likerAPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, likerA);
        long likerB = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-like-b", "likerB", companyId);
        long likerBPrincipalId = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, likerB);

        long recentPostId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, likes_count, created_at) " +
                        "VALUES (?,?,?,?,?, now()) RETURNING id",
                Long.class, userId, userPrincipalId, companyId, "recent", 2
        );
        long oldPostId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, likes_count, created_at) " +
                        "VALUES (?,?,?,?,?, now() - interval '10 days') RETURNING id",
                Long.class, userId, userPrincipalId, companyId, "old", 3
        );

        jdbc.update("INSERT INTO post_likes(liker_principal_id, post_id, created_at) VALUES (?,?, now())",
                likerAPrincipalId, recentPostId);
        jdbc.update("INSERT INTO post_likes(liker_principal_id, post_id, created_at) VALUES (?,?, now() - interval '10 days')",
                likerBPrincipalId, oldPostId);

        String auth = "Bearer " + token("uid-ana");
        mockMvc.perform(get("/v1/me/analytics?window_days=7")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window_days", equalTo(7)))
                .andExpect(jsonPath("$.total_hearts", equalTo(5)))
                .andExpect(jsonPath("$.hearts_last_window", equalTo(1)))
                .andExpect(jsonPath("$.posts_last_window", equalTo(1)));
    }
}
