package com.looped.posts;

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
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class ViewerCapabilitiesIntegrationTest extends PostgresTestBase {

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
    void feed_includes_viewer_capabilities_for_unverified_and_verified_user() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('CapCo', 'cap.co') RETURNING id",
                Long.class
        );
        long authorId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?) RETURNING id",
                Long.class, "uid-cap-author", "capauthor", companyId
        );
        long viewerId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_completed_at) VALUES (?,?,?, now()) RETURNING id",
                Long.class, "uid-cap-viewer", "capviewer", companyId
        );
        long authorPrincipal = jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class, authorId
        );
        jdbc.update("INSERT INTO principals(kind, user_id) VALUES ('user', ?)", viewerId);

        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'CapCo') RETURNING id",
                Long.class
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, expires_at) VALUES (?,?,?,?, NULL)",
                authorId, communityId, "manual", true
        );
        jdbc.update(
                "INSERT INTO posts(author_id, author_principal_id, company_id, community_id, content) VALUES (?,?,?,?,?)",
                authorId, authorPrincipal, companyId, communityId, "cap-post"
        );

        String viewerAuth = "Bearer " + token("uid-cap-viewer");

        mockMvc.perform(get("/v1/feed?communityId=" + communityId).header("Authorization", viewerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.canLike", equalTo(false)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.canComment", equalTo(false)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.canReply", equalTo(false)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.canInteract", equalTo(false)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.canSave", equalTo(true)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.lockReason", equalTo("COMMUNITY_NOT_VERIFIED")))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.lockContext.communityId", equalTo((int) communityId)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.primaryUnlockAction.type", equalTo("VERIFY_COMMUNITY")))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.requiresVerification", equalTo(true)));

        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, expires_at) VALUES (?,?,?,?, NULL)",
                viewerId, communityId, "manual", true
        );

        mockMvc.perform(get("/v1/feed?communityId=" + communityId).header("Authorization", viewerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.canLike", equalTo(true)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.canComment", equalTo(true)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.canReply", equalTo(true)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.canInteract", equalTo(true)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.lockReason", nullValue()));
    }

    @Test
    void feed_sets_poll_vote_capability_for_join_required_specialization() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('PollCapCo', 'pollcap.co') RETURNING id",
                Long.class
        );
        jdbc.update("INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,?)", "uid-poll-author", "pollauthor", companyId);
        long viewerId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_completed_at) VALUES (?,?,?, now()) RETURNING id",
                Long.class, "uid-poll-viewer", "pollviewer", companyId
        );
        long specializationId = jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization','field','Engineering') RETURNING id",
                Long.class
        );
        long companyCommunityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'PollCapCo') RETURNING id",
                Long.class
        );
        long authorId = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid='uid-poll-author'", Long.class);
        jdbc.update(
                "INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?,?)",
                authorId, specializationId
        );

        String authorAuth = "Bearer " + token("uid-poll-author");
        String viewerAuth = "Bearer " + token("uid-poll-viewer");

        String createBody = """
                {
                  "content": "field poll",
                  "communityId": %d,
                  "poll": {
                    "question": "Pick one",
                    "options": ["A", "B"],
                    "maxSelections": 1
                  }
                }
                """.formatted(specializationId);

        mockMvc.perform(post("/v1/posts")
                        .header("Authorization", authorAuth)
                        .header("Idempotency-Key", "viewer-cap-poll-1")
                        .contentType(APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/feed?communityId=" + specializationId).header("Authorization", viewerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].poll").exists())
                .andExpect(jsonPath("$.items[0].viewerCapabilities.canVote", equalTo(false)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.canLike", equalTo(false)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.lockReason", equalTo("SPECIALIZATION_VERIFICATION_REQUIRED")))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.lockContext.requiredVerificationKind", equalTo("company")))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.lockContext.verifyTargetCommunityId", equalTo((int) companyCommunityId)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.lockContext.verifyTargetCommunityName", equalTo("PollCapCo")))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.primaryUnlockAction.type", equalTo("VERIFY_PARENT_THEN_JOIN")))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.primaryUnlockAction.communityId", equalTo((int) companyCommunityId)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.primaryUnlockAction.specializationId", equalTo((int) specializationId)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.requiresJoin", equalTo(true)));

        jdbc.update(
                "INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?,?)",
                viewerId, specializationId
        );

        mockMvc.perform(get("/v1/feed?communityId=" + specializationId).header("Authorization", viewerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.canVote", equalTo(true)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.canLike", equalTo(true)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.canInteract", equalTo(true)))
                .andExpect(jsonPath("$.items[0].viewerCapabilities.lockReason", nullValue()));
    }
}
