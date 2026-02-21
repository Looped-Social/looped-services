package com.looped.users;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/users/me/onboarding-v2")
@Validated
public class OnboardingV2Controller {
    private final OnboardingV2Service onboardingV2;

    public OnboardingV2Controller(OnboardingV2Service onboardingV2) {
        this.onboardingV2 = onboardingV2;
    }

    @PostMapping("/info-screen/viewed")
    public ResponseEntity<?> markInfoScreenViewed(@AuthenticationPrincipal Jwt jwt) {
        return respond(onboardingV2.markInfoScreenViewed(jwt.getSubject()));
    }

    @PutMapping("/org")
    public ResponseEntity<?> setSelectedOrg(@AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody SetOrgRequest body) {
        return respond(onboardingV2.setSelectedOrg(jwt.getSubject(), body.orgId()));
    }

    @PutMapping("/verification-choice")
    public ResponseEntity<?> setVerificationChoice(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody SetVerificationChoiceRequest body) {
        return respond(onboardingV2.setVerificationChoice(jwt.getSubject(), body.verificationPath()));
    }

    @PostMapping("/email-verification/success")
    public ResponseEntity<?> markEmailVerificationSuccess(@AuthenticationPrincipal Jwt jwt) {
        return respond(onboardingV2.markEmailVerificationSuccess(jwt.getSubject()));
    }

    @PostMapping("/specialization")
    public ResponseEntity<?> submitSpecializationSelection(@AuthenticationPrincipal Jwt jwt,
                                                           @Valid @RequestBody SelectSpecializationRequest body) {
        return respond(onboardingV2.submitSpecializationSelection(jwt.getSubject(), body.specializationId()));
    }

    @PostMapping("/skip-explainer/ack")
    public ResponseEntity<?> acknowledgeSkipExplainer(@AuthenticationPrincipal Jwt jwt) {
        return respond(onboardingV2.acknowledgeSkipExplainer(jwt.getSubject()));
    }

    @PostMapping("/photo-pending-explainer/ack")
    public ResponseEntity<?> acknowledgePhotoPendingExplainer(@AuthenticationPrincipal Jwt jwt) {
        return respond(onboardingV2.acknowledgePhotoPendingExplainer(jwt.getSubject()));
    }

    @PostMapping("/finalize")
    public ResponseEntity<?> finalizeOnboarding(@AuthenticationPrincipal Jwt jwt) {
        return respond(onboardingV2.finalizeOnboarding(jwt.getSubject()));
    }

    @PostMapping("/complete-after-community-request")
    public ResponseEntity<?> completeAfterCommunityRequest(@AuthenticationPrincipal Jwt jwt) {
        return respond(onboardingV2.completeAfterCommunityRequest(jwt.getSubject()));
    }

    private ResponseEntity<?> respond(OnboardingV2Service.Result result) {
        return switch (result.status()) {
            case OK -> ResponseEntity.ok(successPayload(result.snapshot()));
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(errorPayload(result));
            case INVALID_STAGE, INVALID_INPUT -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(errorPayload(result));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorPayload(result));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorPayload(result));
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(errorPayload(result));
        };
    }

    private Map<String, Object> successPayload(OnboardingV2Service.Snapshot snapshot) {
        Map<String, Object> body = new HashMap<>();
        body.put("onboarding_complete", snapshot.onboardingComplete());
        body.put("onboarding_step", snapshot.onboardingStep());
        body.put("onboarding_stage_v2", snapshot.onboardingStageV2());
        body.put("onboarding_context", snapshot.onboardingContext());
        return body;
    }

    private Map<String, Object> errorPayload(OnboardingV2Service.Result result) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", result.error());
        body.put("onboarding_step", result.currentStep());
        body.put("current_step", result.currentStep());
        body.put("allowed_next_steps", result.allowedNextSteps());
        body.put("current_stage_v2", result.currentStageV2());
        body.put("allowed_next_stages_v2", result.allowedNextStagesV2());

        if (result.snapshot() != null) {
            body.put("onboarding_complete", result.snapshot().onboardingComplete());
            body.put("onboarding_stage_v2", result.snapshot().onboardingStageV2());
            body.put("onboarding_context", result.snapshot().onboardingContext());
        }

        return body;
    }

    public record SetOrgRequest(
            @JsonAlias("org_id") @NotNull @Positive Long orgId
    ) {}

    public record SetVerificationChoiceRequest(
            @JsonAlias("verification_path") @NotBlank String verificationPath
    ) {}

    public record SelectSpecializationRequest(
            @JsonAlias("specialization_id") @NotNull @Positive Long specializationId
    ) {}
}
