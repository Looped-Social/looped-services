package com.looped.integration;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class CommunityDetailsIntegrationTest extends PostgresTestBase {

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
    void community_details_include_metadata_and_follow_state() throws Exception {
        long companyId = jdbc.queryForObject("INSERT INTO companies(name, domain) VALUES ('DetailCo','detail.co') RETURNING id", Long.class);
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-community-detail", "communitydetail", companyId
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, description) VALUES ('school', 'UNC Chapel Hill', 'Public university') RETURNING id",
                Long.class
        );
        jdbc.update(
                "UPDATE communities SET image_url = ?, short_name = ? WHERE id = ?",
                "https://cdn.example.com/unc.png", "UNC", communityId
        );
        jdbc.update("INSERT INTO community_follows(user_id, community_id) VALUES (?,?)", userId, communityId);

        long otherUserA = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-community-detail-a", "membera", companyId
        );
        long otherUserB = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-community-detail-b", "memberb", companyId
        );
        long otherUserExpired = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-community-detail-expired", "memberexpired", companyId
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, expires_at) VALUES (?,?,?,?, NULL)",
                otherUserA, communityId, "manual", true
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, expires_at) VALUES (?,?,?,?, NULL)",
                otherUserB, communityId, "manual", true
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, expires_at) VALUES (?,?,?,?, now() - interval '1 day')",
                otherUserExpired, communityId, "manual", true
        );

        String auth = "Bearer " + token("uid-community-detail");

        mockMvc.perform(get("/v1/communities/" + communityId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) communityId))
                .andExpect(jsonPath("$.kind").value("school"))
                .andExpect(jsonPath("$.name").value("UNC Chapel Hill"))
                .andExpect(jsonPath("$.short_name").value("UNC"))
                .andExpect(jsonPath("$.description").value("Public university"))
                .andExpect(jsonPath("$.image_url").value("https://cdn.example.com/unc.png"))
                .andExpect(jsonPath("$.member_count").value(2))
                .andExpect(jsonPath("$.is_following").value(true));
    }
}
