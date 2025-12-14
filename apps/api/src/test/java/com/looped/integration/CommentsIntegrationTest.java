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
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, company_id, content) VALUES (?,?,?) RETURNING id",
                Long.class, postAuthorId, companyId, "post body");

        String commenterAuth = "Bearer " + token("uid-commenter");
        String authorAuth = "Bearer " + token("uid-author");
        ObjectMapper mapper = new ObjectMapper();

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
                .andExpect(jsonPath("$.items[0].likes_count").value(1))
                .andExpect(jsonPath("$.items[0].user_liked").value(false))
                .andExpect(jsonPath("$.items[0].liked_by_creator").value(true));
    }
}
