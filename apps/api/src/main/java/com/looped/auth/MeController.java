package com.looped.auth;

import com.looped.users.UserPayloads;
import com.looped.users.OnboardingV2Stages;
import com.looped.users.UsersService;
import com.looped.settings.AppConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class MeController {

    private final UsersService users;
    private final AppConfigService appConfig;
    private final MeNoticesService meNotices;

    public MeController(UsersService users, AppConfigService appConfig, MeNoticesService meNotices) {
        this.users = users;
        this.appConfig = appConfig;
        this.meNotices = meNotices;
    }

    @GetMapping("/v1/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("sub", jwt.getSubject());
        resp.put("iss", jwt.getIssuer() != null ? jwt.getIssuer().toString() : null);
        resp.put("aud", jwt.getAudience());
        resp.put("sign_in_provider", signInProvider(jwt));
        String email = jwt.getClaimAsString("email");
        Boolean emailVerified = jwt.getClaimAsBoolean("email_verified");
        var loginStatus = users.onLogin(jwt.getSubject(), email, emailVerified);
        if (loginStatus == UsersService.LoginStatus.PURGED) {
            var onboardingV2 = users.onboardingStateV2(jwt.getSubject());
            resp.put("provisioned", false);
            resp.put("account_deleted", true);
            resp.put("onboarding_complete", false);
            resp.put("onboarding_step", "profile_setup");
            resp.put("onboarding_stage_v2", onboardingV2.onboardingStageV2());
            resp.put("onboarding_context", onboardingV2.onboardingContext());
            resp.put("error", "account_deleted");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(resp);
        }
        if (loginStatus == UsersService.LoginStatus.PURGE_FAILED
                || loginStatus == UsersService.LoginStatus.DELETE_PENDING) {
            var onboardingV2 = users.onboardingStateV2(jwt.getSubject());
            resp.put("provisioned", false);
            resp.put("account_delete_pending", true);
            resp.put("onboarding_complete", false);
            resp.put("onboarding_step", "profile_setup");
            resp.put("onboarding_stage_v2", onboardingV2.onboardingStageV2());
            resp.put("onboarding_context", onboardingV2.onboardingContext());
            resp.put("error", "account_delete_pending");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(resp);
        }
        if (email != null) {
            resp.put("email", email);
            users.syncEmail(jwt.getSubject(), email);
        }

        var onboarding = users.onboardingState(jwt.getSubject());
        var onboardingV2 = users.onboardingStateV2(jwt.getSubject());
        resp.put("onboarding_complete", onboarding.onboardingComplete());
        resp.put("onboarding_step", onboarding.onboardingStep());
        resp.put("onboarding_stage_v2", onboardingV2.onboardingStageV2());
        resp.put("onboarding_context", onboardingV2.onboardingContext());

        var profile = users.currentProfile(jwt.getSubject());
        if (profile.isEmpty()) {
            resp.put("provisioned", false);
            resp.put("error", "user_not_provisioned");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(resp);
        }
        resp.put("provisioned", true);
        resp.put("user", UserPayloads.fromProfile(profile.get(), true, true, appConfig.defaultProfileImageUrl()));
        resp.put("profile_completion", profileCompletionPayload(users.profileCompletionStatus(
                onboarding.onboardingComplete(),
                profile.get()
        )));
        resp.put("notices", meNotices.pendingNoticesForUserId(profile.get().id()));
        if (!onboarding.onboardingComplete()) {
            var allowedNextStagesV2 = OnboardingV2Stages.allowedNextStages(onboardingV2.onboardingStageV2());
            resp.put("error", "onboarding_incomplete");
            resp.put("current_step", onboarding.onboardingStep());
            resp.put("allowed_next_steps", OnboardingV2Stages.toLegacySteps(allowedNextStagesV2));
            resp.put("current_stage_v2", onboardingV2.onboardingStageV2());
            resp.put("allowed_next_stages_v2", allowedNextStagesV2);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(resp);
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/v1/me/profile-completion/dismiss")
    public ResponseEntity<?> dismissProfileCompletion(@AuthenticationPrincipal Jwt jwt) {
        var result = users.dismissProfileCompletionPrompt(jwt.getSubject());
        if (result.status() == UsersService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before dismissing profile completion prompt"
            ));
        }
        return ResponseEntity.ok(Map.of(
                "profile_completion", profileCompletionPayload(result.profileCompletion())
        ));
    }

    private Map<String, Object> profileCompletionPayload(UsersService.ProfileCompletionStatus profileCompletion) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dismissed_at", profileCompletion.dismissedAt());
        payload.put("completed_at", profileCompletion.completedAt());
        payload.put("missing_photo", profileCompletion.missingPhoto());
        payload.put("missing_bio", profileCompletion.missingBio());
        payload.put("missing_specialization", profileCompletion.missingSpecialization());
        payload.put("should_prompt", profileCompletion.shouldPrompt());
        return payload;
    }

    private String signInProvider(Jwt jwt) {
        Object firebase = jwt.getClaims().get("firebase");
        if (!(firebase instanceof Map<?, ?> m)) return null;
        Object provider = m.get("sign_in_provider");
        return provider != null ? provider.toString() : null;
    }
}
