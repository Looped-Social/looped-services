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

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class DisplayCommunityIntegrationTest extends PostgresTestBase {

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
    void set_display_community_requires_verification() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('DisplayCo','display.co') RETURNING id", Long.class);
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-display", "displayuser", companyId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('school', 'DisplayU') RETURNING id", Long.class);

        String auth = "Bearer " + token("uid-display");

        mockMvc.perform(put("/v1/users/me/display-community")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communityId\":" + communityId + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(equalTo("community_not_verified")));

        long userId = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid = 'uid-display'", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                userId, communityId, "manual", true);

        mockMvc.perform(put("/v1/users/me/display-community")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communityId\":" + communityId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_community.id").value((int) communityId))
                .andExpect(jsonPath("$.display_community.kind").value("school"));
    }

    @Test
    void display_community_appears_on_posts() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('PostDisplayCo','postdisplay.co') RETURNING id", Long.class);
        long authorId = jdbc.queryForObject("INSERT INTO users(firebase_uid, handle, company_id, display_name) VALUES (?,?,?,?) RETURNING id",
                Long.class, "uid-display-post", "displaypost", companyId, "Author");
        long authorPrincipal = jdbc.queryForObject("INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id", Long.class, authorId);
        long communityId = jdbc.queryForObject("INSERT INTO communities(kind, name) VALUES ('company', 'DisplayCo') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?, now())",
                authorId, communityId, "manual", true);
        jdbc.update("UPDATE users SET display_community_id = ? WHERE id = ?", communityId, authorId);
        long postId = jdbc.queryForObject("INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, authorId, authorPrincipal, companyId, communityId, "post body");

        String auth = "Bearer " + token("uid-display-post");

        mockMvc.perform(get("/v1/posts/" + postId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.author_display_community.id").value((int) communityId))
                .andExpect(jsonPath("$.author_display_community.kind").value("company"));
    }
}
