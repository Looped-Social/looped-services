package com.looped.recommendations.people;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app",
        "recommendations.people.active-community-rail-enabled=true"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class PeopleRecommendationsIntegrationTest extends PostgresTestBase {
    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    void rails_enforces_hard_exclusions_and_returns_reasons() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('RecoCo', 'reco.co') RETURNING id",
                Long.class
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, short_name, member_count) VALUES ('school','University of North Carolina','UNC', 10) RETURNING id",
                Long.class
        );

        long viewerId = insertUser("uid-reco-viewer", "reco_viewer", companyId, communityId);
        long mutualCandidateId = insertUser("uid-reco-mutual", "reco_mutual", companyId, null);
        long communityCandidateId = insertUser("uid-reco-community", "reco_community", companyId, communityId);
        long followedId = insertUser("uid-reco-followed", "reco_followed", companyId, null);
        long blockedId = insertUser("uid-reco-blocked", "reco_blocked", companyId, null);
        long commonId = insertUser("uid-reco-common", "reco_common", companyId, null);

        long viewerPrincipalId = insertPrincipal(viewerId);
        long mutualCandidatePrincipalId = insertPrincipal(mutualCandidateId);
        long followedPrincipalId = insertPrincipal(followedId);
        long blockedPrincipalId = insertPrincipal(blockedId);
        long commonPrincipalId = insertPrincipal(commonId);
        insertPrincipal(communityCandidateId);

        jdbc.update("INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)",
                viewerPrincipalId, commonPrincipalId);
        jdbc.update("INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)",
                mutualCandidatePrincipalId, commonPrincipalId);
        jdbc.update("INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)",
                viewerPrincipalId, followedPrincipalId);
        jdbc.update("INSERT INTO principal_blocks(blocker_principal_id, blocked_principal_id) VALUES (?,?)",
                viewerPrincipalId, blockedPrincipalId);

        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) VALUES (?,?, 'email', true, now(), now() + interval '30 days')",
                viewerId, communityId);
        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) VALUES (?,?, 'email', true, now(), now() + interval '30 days')",
                communityCandidateId, communityId);

        String auth = "Bearer " + token("uid-reco-viewer");

        var response = mockMvc.perform(get("/v1/recommendations/people/rails")
                        .param("surface", "search")
                        .param("rails", "pymk,community")
                        .param("community_id", Long.toString(communityId))
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rails", hasSize(2)))
                .andReturn();

        JsonNode root = objectMapper.readTree(response.getResponse().getContentAsString());
        Set<Long> recommendedUserIds = new HashSet<>();
        for (JsonNode rail : root.get("rails")) {
            for (JsonNode item : rail.get("items")) {
                recommendedUserIds.add(item.get("user").get("id").asLong());
                Assertions.assertTrue(item.get("reasons").isArray());
                Assertions.assertTrue(item.get("reasons").size() >= 1);
            }
        }

        Assertions.assertTrue(recommendedUserIds.contains(mutualCandidateId));
        Assertions.assertTrue(recommendedUserIds.contains(communityCandidateId));
        Assertions.assertFalse(recommendedUserIds.contains(followedId));
        Assertions.assertFalse(recommendedUserIds.contains(blockedId));
    }

    @Test
    void feedback_hide_suppresses_candidate_on_next_fetch() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('RecoHideCo', 'recohide.co') RETURNING id",
                Long.class
        );
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name, short_name, member_count) VALUES ('school','Hide University','HIDE', 5) RETURNING id",
                Long.class
        );

        long viewerId = insertUser("uid-reco-hide-viewer", "reco_hide_viewer", companyId, communityId);
        long candidateId = insertUser("uid-reco-hide-cand", "reco_hide_cand", companyId, null);
        long commonId = insertUser("uid-reco-hide-common", "reco_hide_common", companyId, null);

        long viewerPrincipalId = insertPrincipal(viewerId);
        long candidatePrincipalId = insertPrincipal(candidateId);
        long commonPrincipalId = insertPrincipal(commonId);

        jdbc.update("INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)",
                viewerPrincipalId, commonPrincipalId);
        jdbc.update("INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)",
                candidatePrincipalId, commonPrincipalId);

        jdbc.update("INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) VALUES (?,?, 'email', true, now(), now() + interval '30 days')",
                viewerId, communityId);

        String auth = "Bearer " + token("uid-reco-hide-viewer");

        var first = mockMvc.perform(get("/v1/recommendations/people/pymk")
                        .param("surface", "search")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andReturn();

        JsonNode firstRoot = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode firstItem = firstRoot.get("items").get(0);
        String recommendationId = firstItem.get("recommendation_id").asText();
        String trackingToken = firstItem.get("tracking").get("token").asText();
        long returnedCandidateId = firstItem.get("user").get("id").asLong();
        Assertions.assertEquals(candidateId, returnedCandidateId);

        String feedbackBody = """
                {
                  "events": [
                    {
                      "event_id": "%s",
                      "type": "hide",
                      "recommendation_id": "%s",
                      "tracking_token": "%s"
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), recommendationId, trackingToken);

        mockMvc.perform(post("/v1/recommendations/people/feedback")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted", equalTo(1)))
                .andExpect(jsonPath("$.suppressed_candidate_ids", hasSize(1)))
                .andExpect(jsonPath("$.suppressed_candidate_ids[0]", equalTo((int) candidateId)));

        var second = mockMvc.perform(get("/v1/recommendations/people/pymk")
                        .param("surface", "search")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode secondRoot = objectMapper.readTree(second.getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : secondRoot.get("items")) {
            ids.add(item.get("user").get("id").asLong());
        }
        Assertions.assertFalse(ids.contains(candidateId));
    }

    @Test
    void rail_relaxes_exposure_cap_when_all_candidates_are_filtered() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('RecoExposureCo', 'recoexposure.co') RETURNING id",
                Long.class
        );

        long viewerId = insertUser("uid-reco-exposure-viewer", "reco_exposure_viewer", companyId, null);
        long candidateId = insertUser("uid-reco-exposure-cand", "reco_exposure_cand", companyId, null);
        long commonId = insertUser("uid-reco-exposure-common", "reco_exposure_common", companyId, null);

        long viewerPrincipalId = insertPrincipal(viewerId);
        long candidatePrincipalId = insertPrincipal(candidateId);
        long commonPrincipalId = insertPrincipal(commonId);

        jdbc.update("INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)",
                viewerPrincipalId, commonPrincipalId);
        jdbc.update("INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)",
                candidatePrincipalId, commonPrincipalId);

        // Seed three recent exposures to hit the configured 24h cap (default: 3).
        for (int i = 0; i < 3; i++) {
            jdbc.update(
                    "INSERT INTO people_reco_served_audit(" +
                            "request_id, viewer_user_id, candidate_user_id, rail, surface, recommendation_id, tracking_token, " +
                            "reason_codes, reason_texts, rank_score, position, model_version, experiment_key, experiment_bucket, created_at" +
                            ") VALUES (?,?,?,?,?,?,?, '[]'::jsonb, '[]'::jsonb, ?, ?, ?, ?, ?, now() - interval '1 hour')",
                    UUID.randomUUID(),
                    viewerId,
                    candidateId,
                    "pymk",
                    "search",
                    "rec_seed_" + i,
                    "trk_seed_" + UUID.randomUUID(),
                    100L,
                    1,
                    "people-v1-heuristic",
                    "people_reco_v1",
                    "A"
            );
        }

        String auth = "Bearer " + token("uid-reco-exposure-viewer");

        var response = mockMvc.perform(get("/v1/recommendations/people/pymk")
                        .param("surface", "search")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andReturn();

        JsonNode root = objectMapper.readTree(response.getResponse().getContentAsString());
        long returnedCandidateId = root.get("items").get(0).get("user").get("id").asLong();
        Assertions.assertEquals(candidateId, returnedCandidateId);
    }

    @Test
    void rail_falls_back_to_handle_when_candidate_display_name_is_null() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('RecoNameCo', 'reconame.co') RETURNING id",
                Long.class
        );

        long viewerId = insertUser("uid-reco-name-viewer", "reco_name_viewer", companyId, null);
        long candidateId = insertUser("uid-reco-name-cand", "reco_name_cand", companyId, null);
        long commonId = insertUser("uid-reco-name-common", "reco_name_common", companyId, null);

        long viewerPrincipalId = insertPrincipal(viewerId);
        long candidatePrincipalId = insertPrincipal(candidateId);
        long commonPrincipalId = insertPrincipal(commonId);

        jdbc.update("INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)",
                viewerPrincipalId, commonPrincipalId);
        jdbc.update("INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?,?)",
                candidatePrincipalId, commonPrincipalId);

        jdbc.update("UPDATE users SET display_name = NULL WHERE id = ?", candidateId);

        String auth = "Bearer " + token("uid-reco-name-viewer");

        mockMvc.perform(get("/v1/recommendations/people/pymk")
                        .param("surface", "search")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].user.id", equalTo((int) candidateId)))
                .andExpect(jsonPath("$.items[0].user.handle", equalTo("reco_name_cand")))
                .andExpect(jsonPath("$.items[0].user.display_name", equalTo("reco_name_cand")));
    }

    @Test
    void invalid_cursor_returns_400() throws Exception {
        long companyId = jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES ('RecoCursorCo', 'recocursor.co') RETURNING id",
                Long.class
        );
        insertUser("uid-reco-cursor-viewer", "reco_cursor_viewer", companyId, null);

        String auth = "Bearer " + token("uid-reco-cursor-viewer");

        mockMvc.perform(get("/v1/recommendations/people/pymk")
                        .param("surface", "search")
                        .param("cursor", "invalid-cursor")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", equalTo("invalid_cursor")));
    }

    private long insertUser(String firebaseUid, String handle, long companyId, Long displayCommunityId) {
        return jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, display_name, display_community_id, onboarding_step, onboarding_completed_at) " +
                        "VALUES (?,?,?,?,?,'verification_notifications', now()) RETURNING id",
                Long.class,
                firebaseUid,
                handle,
                companyId,
                handle,
                displayCommunityId
        );
    }

    private long insertPrincipal(long userId) {
        return jdbc.queryForObject(
                "INSERT INTO principals(kind, user_id) VALUES ('user', ?) RETURNING id",
                Long.class,
                userId
        );
    }
}
