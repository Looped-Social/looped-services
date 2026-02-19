package com.looped.admin;

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
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
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
class AdminCommunitiesListIntegrationTest extends PostgresTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminUsersRepository admins;

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

    private String adminAuth(String uid, String email) {
        admins.insert(null, email, "admin", "active", List.of(AdminPermissions.CREATE_COMMUNITY));
        return "Bearer " + token(uid, email);
    }

    @Test
    void list_returns_total_count_for_unfiltered_results() throws Exception {
        String auth = adminAuth("admin-community-list", "admin-community-list@looped.com");

        jdbc.update("INSERT INTO communities(kind, name) VALUES ('company', 'Acme Company')");
        jdbc.update("INSERT INTO communities(kind, name) VALUES ('school', 'Acme School')");
        jdbc.update("INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization', 'field', 'Computer Science')");

        mockMvc.perform(get("/v1/admin/communities")
                        .header("Authorization", auth)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andExpect(jsonPath("$.total_count", equalTo(3)));
    }

    @Test
    void list_with_query_and_kind_returns_accurate_total_count() throws Exception {
        String auth = adminAuth("admin-community-kind-query", "admin-community-kind-query@looped.com");

        jdbc.update("INSERT INTO communities(kind, name) VALUES ('company', 'Acme Data')");
        jdbc.update("INSERT INTO communities(kind, name) VALUES ('company', 'Acme Analytics')");
        jdbc.update("INSERT INTO communities(kind, name) VALUES ('school', 'Acme University')");
        jdbc.update("INSERT INTO communities(kind, name) VALUES ('company', 'Beta Labs')");

        mockMvc.perform(get("/v1/admin/communities")
                        .header("Authorization", auth)
                        .param("kind", "company")
                        .param("query", "acme")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andExpect(jsonPath("$.total_count", equalTo(2)));
    }

    @Test
    void list_supports_multi_kind_filter_and_keeps_total_count_stable_across_pages() throws Exception {
        String auth = adminAuth("admin-community-kinds", "admin-community-kinds@looped.com");

        jdbc.update("INSERT INTO communities(kind, name) VALUES ('company', 'Northwind')");
        jdbc.update("INSERT INTO communities(kind, name) VALUES ('school', 'State University')");
        jdbc.update("INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization', 'major', 'Economics')");
        jdbc.update("INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization', 'field', 'Finance')");

        var page1 = mockMvc.perform(get("/v1/admin/communities")
                        .header("Authorization", auth)
                        .param("kinds", "company,school,major,field")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.total_count", equalTo(4)))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        String next = com.jayway.jsonpath.JsonPath.read(
                page1.getResponse().getContentAsString(),
                "$.next_cursor"
        );

        mockMvc.perform(get("/v1/admin/communities")
                        .header("Authorization", auth)
                        .param("kinds", "company,school,major,field")
                        .param("limit", "2")
                        .param("cursor", next))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.total_count", equalTo(4)));
    }

    @Test
    void list_rejects_invalid_kinds_token() throws Exception {
        String auth = adminAuth("admin-community-invalid-kind", "admin-community-invalid-kind@looped.com");

        mockMvc.perform(get("/v1/admin/communities")
                        .header("Authorization", auth)
                        .param("kinds", "company,invalid_kind"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", equalTo("invalid_kind")));
    }
}
