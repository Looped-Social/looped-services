package com.looped.admin;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
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
class AdminPostsSearchIntegrationTest extends PostgresTestBase {
    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AdminUsersRepository admins;

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
    void search_returns_typo_matches() throws Exception {
        admins.insert(null, "posts@looped.com", "admin", "active", List.of(AdminPermissions.VIEW_POSTS));
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('SearchCo', 'search.co') RETURNING id",
                Long.class);
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'SearchCo') RETURNING id",
                Long.class);
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-search-author", "sally", companyId);
        long authorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, authorId);
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, communityId, "Need interview prep tips");

        String auth = "Bearer " + token("admin-posts-1", "posts@looped.com");

        mockMvc.perform(get("/v1/admin/posts/search")
                        .param("query", "interveiw")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[*].id", hasItem(((Number) postId).intValue())))
                .andExpect(jsonPath("$.items[0].content_snippet", equalTo("Need interview prep tips")))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void search_supports_pagination_and_status_filters() throws Exception {
        admins.insert(null, "posts@looped.com", "admin", "active", List.of(AdminPermissions.VIEW_POSTS));
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('PagerCo', 'pager.co') RETURNING id",
                Long.class);
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-pager-author", "pat", companyId);
        long authorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, authorId);

        long p1 = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, "interview one", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        long p2 = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, "interview two", OffsetDateTime.parse("2026-01-02T00:00:00Z"));
        long p3 = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content, created_at) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, "interview three", OffsetDateTime.parse("2026-01-03T00:00:00Z"));
        jdbc.update("UPDATE posts SET removed_at = now() WHERE id = ?", p2);

        String auth = "Bearer " + token("admin-posts-2", "posts@looped.com");

        var r1 = mockMvc.perform(get("/v1/admin/posts/search")
                        .param("query", "interview")
                        .param("status", "all")
                        .param("limit", "2")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String next = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(r1.getResponse().getContentAsString())
                .get("next_cursor").asText();

        mockMvc.perform(get("/v1/admin/posts/search")
                        .param("query", "interview")
                        .param("status", "all")
                        .param("limit", "2")
                        .param("cursor", next)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());

        mockMvc.perform(get("/v1/admin/posts/search")
                        .param("query", "interview")
                        .param("status", "removed")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo(((Number) p2).intValue())));

        mockMvc.perform(get("/v1/admin/posts/search")
                        .param("query", "interview")
                        .param("status", "active")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].id", hasItem(((Number) p1).intValue())))
                .andExpect(jsonPath("$.items[*].id", hasItem(((Number) p3).intValue())))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void search_treats_numeric_query_as_post_id_lookup() throws Exception {
        admins.insert(null, "posts@looped.com", "admin", "active", List.of(AdminPermissions.VIEW_POSTS));
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('NumCo', 'num.co') RETURNING id",
                Long.class);
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-num-author", "nina", companyId);
        long authorPrincipalId = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, authorId);
        long postId = jdbc.queryForObject(
                "INSERT INTO posts(author_id, author_principal_id, company_id, content) VALUES (?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipalId, companyId, "numeric lookup");

        String auth = "Bearer " + token("admin-posts-3", "posts@looped.com");

        mockMvc.perform(get("/v1/admin/posts/search")
                        .param("query", "#" + postId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", equalTo(((Number) postId).intValue())));
    }
}
