package com.looped.users;

import com.looped.auth.TestSecurityConfig;
import com.looped.support.PostgresTestBase;
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
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.issuer=http://test-issuer",
        "auth.audience=test-app"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurityConfig.class)
class OnboardingV2IntegrationTest extends PostgresTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private String token(String sub) {
        return token(sub, sub + "@example.com");
    }

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

    private String auth(String sub) {
        return "Bearer " + token(sub);
    }

    private long company(String name, String domain) {
        return jdbc.queryForObject(
                "INSERT INTO companies(name, domain) VALUES (?,?) RETURNING id",
                Long.class,
                name,
                domain
        );
    }

    private long user(String firebaseUid, String handle, long companyId) {
        return jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, onboarding_step, onboarding_completed_at) VALUES (?,?,?,?,NULL) RETURNING id",
                Long.class,
                firebaseUid,
                handle,
                companyId,
                "profile_setup"
        );
    }

    private long community(String kind, String name) {
        return jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES (?, ?) RETURNING id",
                Long.class,
                kind,
                name
        );
    }

    private long specialization(String type, String name) {
        return jdbc.queryForObject(
                "INSERT INTO communities(kind, specialization_type, name) VALUES ('specialization', ?, ?) RETURNING id",
                Long.class,
                type,
                name
        );
    }

    @Test
    void skip_branch_completes_and_preserves_legacy_contract() throws Exception {
        long companyId = company("SkipCo", "skipco.com");
        long userId = user("uid-onb-v2-skip", "skipper", companyId);
        long orgId = community("company", "Skip Org");

        String auth = auth("uid-onb-v2-skip");

        mockMvc.perform(post("/v1/users/me/onboarding-v2/info-screen/viewed")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("posting_info")))
                .andExpect(jsonPath("$.onboarding_step", equalTo("profile_setup")));

        mockMvc.perform(put("/v1/users/me/onboarding-v2/org")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("org_selected")))
                .andExpect(jsonPath("$.onboarding_step", equalTo("select_company")))
                .andExpect(jsonPath("$.onboarding_context.selected_org_id", equalTo((int) orgId)))
                .andExpect(jsonPath("$.onboarding_context.selected_org_kind", equalTo("company")));

        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"skip\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("skip_explainer")))
                .andExpect(jsonPath("$.onboarding_step", equalTo("verification")));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/skip-explainer/ack")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_context.milestones.skip_explainer_ack_at", notNullValue()));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/finalize")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_complete", equalTo(true)))
                .andExpect(jsonPath("$.onboarding_step", equalTo("verification_notifications")))
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("completed")))
                .andExpect(jsonPath("$.onboarding_context.completion_reason", equalTo("skipped_verification")));

        mockMvc.perform(get("/v1/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_step", equalTo("verification_notifications")))
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("completed")));

        Integer completed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ? AND onboarding_completed_at IS NOT NULL",
                Integer.class,
                userId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, completed.intValue());
    }

    @Test
    void email_branch_requires_specialization_before_completion() throws Exception {
        long companyId = company("EmailCo", "emailco.com");
        long userId = user("uid-onb-v2-email", "emailer", companyId);
        long orgId = community("company", "Email Org");
        long fieldId = specialization("field", "Product Engineering");

        String auth = auth("uid-onb-v2-email");

        mockMvc.perform(post("/v1/users/me/onboarding-v2/info-screen/viewed").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/org")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"email\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("email_verification")));

        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?,now())",
                userId,
                orgId,
                "email",
                true
        );

        mockMvc.perform(post("/v1/users/me/onboarding-v2/email-verification/success")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("specialization_selection")))
                .andExpect(jsonPath("$.onboarding_context.requires_specialization_selection", equalTo(true)));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/finalize")
                        .header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", equalTo("specialization_required")));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/specialization")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"specializationId\":" + fieldId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_context.selected_specialization_id", equalTo((int) fieldId)));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/finalize")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_complete", equalTo(true)))
                .andExpect(jsonPath("$.onboarding_context.completion_reason", equalTo("email_verified_and_joined")));

        Integer joined = jdbc.queryForObject(
                "SELECT COUNT(*) FROM specialization_joins WHERE user_id = ? AND specialization_id = ?",
                Integer.class,
                userId,
                fieldId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, joined.intValue());

        Long displaySpecializationId = jdbc.queryForObject(
                "SELECT display_specialization_id FROM users WHERE id = ?",
                Long.class,
                userId
        );
        org.junit.jupiter.api.Assertions.assertEquals(fieldId, displaySpecializationId);

        Long displayCommunityId = jdbc.queryForObject(
                "SELECT display_community_id FROM users WHERE id = ?",
                Long.class,
                userId
        );
        org.junit.jupiter.api.Assertions.assertEquals(orgId, displayCommunityId);
    }

    @Test
    void email_branch_finalize_preserves_existing_display_specialization() throws Exception {
        long companyId = company("EmailKeepCo", "emailkeepco.com");
        long userId = user("uid-onb-v2-email-keep", "emailerkeep", companyId);
        long orgId = community("company", "Email Keep Org");
        long onboardingSpecializationId = specialization("field", "Onboarding Selected");
        long existingSpecializationId = specialization("major", "Already Chosen");
        long existingDisplayCommunityId = community("school", "Already Displayed School");

        jdbc.update(
                "INSERT INTO specialization_joins(user_id, specialization_id) VALUES (?, ?)",
                userId,
                existingSpecializationId
        );
        jdbc.update(
                "UPDATE users SET display_specialization_id = ?, display_community_id = ? WHERE id = ?",
                existingSpecializationId,
                existingDisplayCommunityId,
                userId
        );

        String auth = auth("uid-onb-v2-email-keep");

        mockMvc.perform(post("/v1/users/me/onboarding-v2/info-screen/viewed").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/org")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"email\"}"))
                .andExpect(status().isOk());

        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?,now())",
                userId,
                orgId,
                "email",
                true
        );

        mockMvc.perform(post("/v1/users/me/onboarding-v2/email-verification/success")
                        .header("Authorization", auth))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/users/me/onboarding-v2/specialization")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"specializationId\":" + onboardingSpecializationId + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/users/me/onboarding-v2/finalize")
                        .header("Authorization", auth))
                .andExpect(status().isOk());

        Long displaySpecializationId = jdbc.queryForObject(
                "SELECT display_specialization_id FROM users WHERE id = ?",
                Long.class,
                userId
        );
        org.junit.jupiter.api.Assertions.assertEquals(existingSpecializationId, displaySpecializationId);

        Long displayCommunityId = jdbc.queryForObject(
                "SELECT display_community_id FROM users WHERE id = ?",
                Long.class,
                userId
        );
        org.junit.jupiter.api.Assertions.assertEquals(existingDisplayCommunityId, displayCommunityId);
    }

    @Test
    void photo_branch_requires_pending_request_then_completes() throws Exception {
        long companyId = company("PhotoCo", "photoco.com");
        long userId = user("uid-onb-v2-photo", "photoflow", companyId);
        long orgId = community("company", "PhotoCo");

        String auth = auth("uid-onb-v2-photo");

        mockMvc.perform(post("/v1/users/me/onboarding-v2/info-screen/viewed").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/org")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"photo_id\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("photo_id_verification")));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/photo-pending-explainer/ack")
                        .header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", equalTo("photo_verification_not_pending")));

        jdbc.update(
                "INSERT INTO verification_requests(user_id, community_id, method, status) VALUES (?, ?, 'photo_id', 'pending')",
                userId,
                orgId
        );

        mockMvc.perform(post("/v1/users/me/onboarding-v2/photo-pending-explainer/ack")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("photo_pending_explainer")))
                .andExpect(jsonPath("$.onboarding_context.verification_status", equalTo("pending")));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/finalize")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_complete", equalTo(true)))
                .andExpect(jsonPath("$.onboarding_context.completion_reason", equalTo("photo_pending")));

        Long displayCommunityId = jdbc.queryForObject(
                "SELECT display_community_id FROM users WHERE id = ?",
                Long.class,
                userId
        );
        org.junit.jupiter.api.Assertions.assertNull(displayCommunityId);
    }

    @Test
    void invalid_transition_returns_stage_metadata() throws Exception {
        long companyId = company("InvalidCo", "invalidco.com");
        user("uid-onb-v2-invalid", "invalidv2", companyId);

        String auth = auth("uid-onb-v2-invalid");

        mockMvc.perform(post("/v1/users/me/onboarding-v2/finalize")
                        .header("Authorization", auth))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", equalTo("invalid_onboarding_stage")))
                .andExpect(jsonPath("$.current_step", containsString("profile_setup")))
                .andExpect(jsonPath("$.current_stage_v2", equalTo("profile_setup")))
                .andExpect(jsonPath("$.allowed_next_stages_v2", hasItem("posting_info")))
                .andExpect(jsonPath("$.allowed_next_steps", hasItem("profile_setup")));
    }

    @Test
    void complete_after_community_request_marks_onboarding_complete_without_selected_org() throws Exception {
        long companyId = company("RequestCompleteCo", "requestcomplete.com");
        long userId = user("uid-onb-v2-community-complete", "reqcomplete", companyId);
        String auth = auth("uid-onb-v2-community-complete");

        jdbc.update(
                "INSERT INTO community_requests(user_id, kind, name, description, status) VALUES (?,?,?,?, 'pending')",
                userId,
                "company",
                "University of North Carolina",
                "Need this community"
        );

        mockMvc.perform(post("/v1/users/me/onboarding-v2/complete-after-community-request")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_complete", equalTo(true)))
                .andExpect(jsonPath("$.onboarding_step", equalTo("verification_notifications")))
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("completed")))
                .andExpect(jsonPath("$.onboarding_context.completion_reason", equalTo("skipped_verification")))
                .andExpect(jsonPath("$.onboarding_context.verification_path", equalTo("skip")))
                .andExpect(jsonPath("$.onboarding_context.selected_org_id", nullValue()));

        mockMvc.perform(get("/v1/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("completed")))
                .andExpect(jsonPath("$.onboarding_step", equalTo("verification_notifications")));

        Integer completed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ? AND onboarding_completed_at IS NOT NULL",
                Integer.class,
                userId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, completed.intValue());
    }

    @Test
    void complete_after_community_request_requires_pending_org_request() throws Exception {
        long companyId = company("RequestMissingCo", "requestmissing.com");
        user("uid-onb-v2-community-missing", "reqmissing", companyId);
        String auth = auth("uid-onb-v2-community-missing");

        mockMvc.perform(post("/v1/users/me/onboarding-v2/complete-after-community-request")
                        .header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", equalTo("community_request_required")))
                .andExpect(jsonPath("$.current_stage_v2", equalTo("profile_setup")))
                .andExpect(jsonPath("$.allowed_next_stages_v2", hasItem("posting_info")));
    }

    @Test
    void complete_after_community_request_is_idempotent() throws Exception {
        long companyId = company("RequestIdempotentCo", "requestidempotent.com");
        long userId = user("uid-onb-v2-community-idempotent", "reqidempotent", companyId);
        String auth = auth("uid-onb-v2-community-idempotent");

        jdbc.update(
                "INSERT INTO community_requests(user_id, kind, name, description, status) VALUES (?,?,?,?, 'pending')",
                userId,
                "company",
                "UNC",
                "Need this community"
        );

        mockMvc.perform(post("/v1/users/me/onboarding-v2/complete-after-community-request")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_complete", equalTo(true)));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/complete-after-community-request")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_complete", equalTo(true)))
                .andExpect(jsonPath("$.onboarding_context.completion_reason", equalTo("skipped_verification")));
    }

    @Test
    void verification_choice_skip_from_choice_stage_returns_ok() throws Exception {
        long companyId = company("ChoiceSkipCo", "choiceskip.com");
        user("uid-onb-v2-choice-skip", "choiceskip", companyId);
        long orgId = community("company", "Choice Skip Org");
        String auth = auth("uid-onb-v2-choice-skip");

        mockMvc.perform(post("/v1/users/me/onboarding-v2/info-screen/viewed").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/org")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("org_selected")));

        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"skip\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_complete", equalTo(false)))
                .andExpect(jsonPath("$.onboarding_step", equalTo("verification")))
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("skip_explainer")))
                .andExpect(jsonPath("$.onboarding_context.verification_path", equalTo("skip")));
    }

    @Test
    void verification_choice_skip_from_email_verification_returns_ok() throws Exception {
        long companyId = company("EmailSkipCo", "emailskip.com");
        user("uid-onb-v2-email-skip", "emailskip", companyId);
        long orgId = community("company", "Email Skip Org");
        String auth = auth("uid-onb-v2-email-skip");

        mockMvc.perform(post("/v1/users/me/onboarding-v2/info-screen/viewed").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/org")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"email\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("email_verification")));

        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"skip\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_complete", equalTo(false)))
                .andExpect(jsonPath("$.onboarding_step", equalTo("verification")))
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("skip_explainer")))
                .andExpect(jsonPath("$.onboarding_context.verification_path", equalTo("skip")));
    }

    @Test
    void invalid_stage_from_email_verification_includes_skip_explainer_in_allowed_next_stages() throws Exception {
        long companyId = company("EmailAllowedCo", "emailallowed.com");
        user("uid-onb-v2-email-allowed", "emailallowed", companyId);
        long orgId = community("company", "Email Allowed Org");
        String auth = auth("uid-onb-v2-email-allowed");

        mockMvc.perform(post("/v1/users/me/onboarding-v2/info-screen/viewed").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/org")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"email\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("email_verification")));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/photo-pending-explainer/ack")
                        .header("Authorization", auth))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", equalTo("invalid_onboarding_stage")))
                .andExpect(jsonPath("$.current_stage_v2", equalTo("email_verification")))
                .andExpect(jsonPath("$.allowed_next_stages_v2", hasItem("specialization_selection")))
                .andExpect(jsonPath("$.allowed_next_stages_v2", hasItem("skip_explainer")));
    }

    @Test
    void undo_skip_to_email_from_skip_explainer_returns_ok() throws Exception {
        long companyId = company("UndoSkipEmailCo", "undoskipemail.com");
        user("uid-onb-v2-undo-skip-email", "undoskipemail", companyId);
        long orgId = community("company", "Undo Skip Email Org");
        String auth = auth("uid-onb-v2-undo-skip-email");

        mockMvc.perform(post("/v1/users/me/onboarding-v2/info-screen/viewed").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/org")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"skip\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("skip_explainer")));

        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"email\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_complete", equalTo(false)))
                .andExpect(jsonPath("$.onboarding_step", equalTo("verification")))
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("email_verification")))
                .andExpect(jsonPath("$.onboarding_context.verification_path", equalTo("email")))
                .andExpect(jsonPath("$.onboarding_context.completion_reason", nullValue()));
    }

    @Test
    void undo_skip_to_photo_id_from_skip_explainer_returns_ok() throws Exception {
        long companyId = company("UndoSkipPhotoCo", "undoskipphoto.com");
        user("uid-onb-v2-undo-skip-photo", "undoskipphoto", companyId);
        long orgId = community("company", "Undo Skip Photo Org");
        String auth = auth("uid-onb-v2-undo-skip-photo");

        mockMvc.perform(post("/v1/users/me/onboarding-v2/info-screen/viewed").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/org")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"skip\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("skip_explainer")));

        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"photo_id\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_complete", equalTo(false)))
                .andExpect(jsonPath("$.onboarding_step", equalTo("verification")))
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("photo_id_verification")))
                .andExpect(jsonPath("$.onboarding_context.verification_path", equalTo("photo_id")))
                .andExpect(jsonPath("$.onboarding_context.completion_reason", nullValue()));
    }

    @Test
    void rejected_action_from_skip_explainer_returns_standard_metadata() throws Exception {
        long companyId = company("SkipMetaCo", "skipmeta.com");
        user("uid-onb-v2-skip-meta", "skipmeta", companyId);
        long orgId = community("company", "Skip Meta Org");
        String auth = auth("uid-onb-v2-skip-meta");

        mockMvc.perform(post("/v1/users/me/onboarding-v2/info-screen/viewed").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/org")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"skip\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("skip_explainer")));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/email-verification/success")
                        .header("Authorization", auth))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", equalTo("invalid_onboarding_stage")))
                .andExpect(jsonPath("$.current_stage_v2", equalTo("skip_explainer")))
                .andExpect(jsonPath("$.allowed_next_stages_v2", hasItem("completed")))
                .andExpect(jsonPath("$.allowed_next_stages_v2", hasItem("email_verification")))
                .andExpect(jsonPath("$.allowed_next_stages_v2", hasItem("photo_id_verification")))
                .andExpect(jsonPath("$.current_step", equalTo("verification")))
                .andExpect(jsonPath("$.allowed_next_steps", hasItem("verification")))
                .andExpect(jsonPath("$.allowed_next_steps", hasItem("verification_notifications")));
    }

    @Test
    void me_keeps_legacy_onboarding_step_enum_while_exposing_v2_stage() throws Exception {
        long companyId = company("LegacyEnumCo", "legacyenum.com");
        long userId = user("uid-onb-v2-legacy-enum", "legacyenum", companyId);
        long orgId = community("company", "Legacy Org");

        String auth = auth("uid-onb-v2-legacy-enum");

        mockMvc.perform(get("/v1/me").header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.onboarding_step", equalTo("profile_setup")))
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("profile_setup")));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/info-screen/viewed").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/org")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"skip\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/me").header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.onboarding_step", equalTo("verification")))
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("skip_explainer")));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/skip-explainer/ack").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/users/me/onboarding-v2/finalize").header("Authorization", auth))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_step", equalTo("verification_notifications")))
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("completed")));

        Integer complete = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ? AND onboarding_completed_at IS NOT NULL",
                Integer.class,
                userId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, complete.intValue());
    }

    @Test
    void specialization_join_blocked_for_skip_and_photo_pending_branches_until_selected_org_is_approved() throws Exception {
        long companyId = company("BlockJoinCo", "blockjoin.com");
        long skipUserId = user("uid-onb-v2-skip-block", "skipblock", companyId);
        long photoUserId = user("uid-onb-v2-photo-block", "photoblock", companyId);

        long skipSelectedOrgId = community("company", "Skip Selected Org");
        long skipOtherVerifiedCompany = community("company", "Skip Other Verified Company");
        long fieldId = specialization("field", "Onboarding Blocked Field");

        long photoSelectedOrgId = community("company", "Photo Selected Org");
        long photoOtherVerifiedCompany = community("company", "Photo Other Verified Company");
        long photoFieldId = specialization("field", "Onboarding Blocked Field Two");

        String skipAuth = auth("uid-onb-v2-skip-block");
        mockMvc.perform(post("/v1/users/me/onboarding-v2/info-screen/viewed").header("Authorization", skipAuth)).andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/org")
                        .header("Authorization", skipAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + skipSelectedOrgId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", skipAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"skip\"}"))
                .andExpect(status().isOk());

        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?,now())",
                skipUserId,
                skipOtherVerifiedCompany,
                "manual",
                true
        );

        mockMvc.perform(post("/v1/specializations/" + fieldId + "/join").header("Authorization", skipAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("specialization_verification_required")))
                .andExpect(jsonPath("$.required_verification_kind", equalTo("company")));

        String photoAuth = auth("uid-onb-v2-photo-block");
        mockMvc.perform(post("/v1/users/me/onboarding-v2/info-screen/viewed").header("Authorization", photoAuth)).andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/org")
                        .header("Authorization", photoAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + photoSelectedOrgId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", photoAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"photo_id\"}"))
                .andExpect(status().isOk());

        jdbc.update(
                "INSERT INTO verification_requests(user_id, community_id, method, status) VALUES (?, ?, 'photo_id', 'pending')",
                photoUserId,
                photoSelectedOrgId
        );
        mockMvc.perform(post("/v1/users/me/onboarding-v2/photo-pending-explainer/ack")
                        .header("Authorization", photoAuth))
                .andExpect(status().isOk());

        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?,now())",
                photoUserId,
                photoOtherVerifiedCompany,
                "manual",
                true
        );

        mockMvc.perform(post("/v1/specializations/" + photoFieldId + "/join").header("Authorization", photoAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", equalTo("specialization_verification_required")))
                .andExpect(jsonPath("$.required_verification_kind", equalTo("company")));
    }

    @Test
    void resume_is_deterministic_from_major_stages() throws Exception {
        long companyId = company("ResumeCo", "resumeco.com");
        long userId = user("uid-onb-v2-resume", "resumev2", companyId);
        long orgId = community("company", "Resume Org");
        long fieldId = specialization("field", "Resume Field");
        String auth = auth("uid-onb-v2-resume");

        mockMvc.perform(get("/v1/me").header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("profile_setup")));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/info-screen/viewed").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/me").header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("posting_info")));

        mockMvc.perform(put("/v1/users/me/onboarding-v2/org")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/me").header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("org_selected")));

        mockMvc.perform(put("/v1/users/me/onboarding-v2/verification-choice")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPath\":\"email\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/me").header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("email_verification")));

        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at) VALUES (?,?,?,?,now())",
                userId,
                orgId,
                "email",
                true
        );
        mockMvc.perform(post("/v1/users/me/onboarding-v2/email-verification/success")
                        .header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/me").header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("specialization_selection")));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/specialization")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"specializationId\":" + fieldId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/me").header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("specialization_selection")));

        mockMvc.perform(post("/v1/users/me/onboarding-v2/finalize").header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_stage_v2", equalTo("completed")))
                .andExpect(jsonPath("$.onboarding_step", equalTo("verification_notifications")));
    }
}
