package com.looped.auth;

import com.looped.users.UserPayloads;
import com.looped.users.UsersService;
import com.looped.settings.AppConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class MeController {

    private final UsersService users;
    private final AppConfigService appConfig;

    public MeController(UsersService users, AppConfigService appConfig) {
        this.users = users;
        this.appConfig = appConfig;
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
            resp.put("provisioned", false);
            resp.put("account_deleted", true);
            resp.put("onboarding_complete", false);
            resp.put("onboarding_step", "profile_setup");
            resp.put("error", "account_deleted");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(resp);
        }
        if (loginStatus == UsersService.LoginStatus.PURGE_FAILED) {
            resp.put("provisioned", false);
            resp.put("account_delete_pending", true);
            resp.put("onboarding_complete", false);
            resp.put("onboarding_step", "profile_setup");
            resp.put("error", "account_delete_pending");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(resp);
        }
        if (email != null) {
            resp.put("email", email);
            users.syncEmail(jwt.getSubject(), email);
        }

        var onboarding = users.onboardingState(jwt.getSubject());
        resp.put("onboarding_complete", onboarding.onboardingComplete());
        resp.put("onboarding_step", onboarding.onboardingStep());

        var profile = users.currentProfile(jwt.getSubject());
        if (profile.isEmpty()) {
            resp.put("provisioned", false);
            resp.put("error", "user_not_provisioned");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(resp);
        }
        resp.put("provisioned", true);
        resp.put("user", UserPayloads.fromProfile(profile.get(), true, true, appConfig.defaultProfileImageUrl()));
        if (!onboarding.onboardingComplete()) {
            resp.put("error", "onboarding_incomplete");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(resp);
        }
        return ResponseEntity.ok(resp);
    }

    private String signInProvider(Jwt jwt) {
        Object firebase = jwt.getClaims().get("firebase");
        if (!(firebase instanceof Map<?, ?> m)) return null;
        Object provider = m.get("sign_in_provider");
        return provider != null ? provider.toString() : null;
    }
}
