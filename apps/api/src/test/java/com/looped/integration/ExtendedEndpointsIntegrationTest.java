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

import static org.hamcrest.Matchers.*;
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
        jdbc.update("INSERT INTO follows(follower_id, followee_id) VALUES (?,?)", followerId, userId);
        long post1 = jdbc.queryForObject("INSERT INTO posts(author_id, company_id, content) VALUES (?,?,?) RETURNING id",
                Long.class, userId, companyId, "first");
        jdbc.update("INSERT INTO posts(author_id, company_id, content) VALUES (?,?,?)", userId, companyId, "second");
        jdbc.update("INSERT INTO comments(post_id, user_id, company_id, content) VALUES (?,?,?,?)", post1, userId, companyId, "first-comment");

        String auth = "Bearer " + token("uid-profile");

        mockMvc.perform(put("/v1/users/me")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"New Name\",\"bio\":\"Updated bio\",\"isAnonymous\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("New Name"))
                .andExpect(jsonPath("$.stats.posts_count").value(2))
                .andExpect(jsonPath("$.stats.follower_count").value(1))
                .andExpect(jsonPath("$.stats.comments_count").value(1));

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
                        .content("{\"content\":\"hello\",\"attachments\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("hello"));

        mockMvc.perform(get("/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sender_id", equalTo((int) actorId)))
                .andExpect(jsonPath("$.items[0].content", equalTo("hello")));

        mockMvc.perform(get("/v1/conversations")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].last_message", equalTo("hello")))
                .andExpect(jsonPath("$.items[0].other_user_profile.handle", equalTo("bravo")));
    }

    @Test
    void channels_and_notifications_and_search_directory() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('ChannelCo','ch.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-channel", "charlie", companyId);
        long chanId = jdbc.queryForObject("INSERT INTO channels(company_id, name, is_public) VALUES (?,?, true) RETURNING id",
                Long.class, companyId, "general");
        jdbc.update("INSERT INTO notifications(user_id, type, payload) VALUES (?,?, ?::jsonb)", userId, "channel.mention",
                "{\"channel_id\":" + chanId + "}");

        String auth = "Bearer " + token("uid-channel");

        mockMvc.perform(get("/v1/channels")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name", equalTo("general")))
                .andExpect(jsonPath("$.items[0].member_count", greaterThanOrEqualTo(0)));

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
}
