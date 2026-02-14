package com.looped.auth;

import com.looped.settings.AppConfigService;
import com.looped.users.UsersService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeControllerClaimsTest {

    @Test
    void me_includes_sign_in_provider_from_firebase_claim() {
        UsersService users = mock(UsersService.class);
        AppConfigService appConfig = mock(AppConfigService.class);
        MeController controller = new MeController(users, appConfig);

        when(users.onLogin("uid", "test@example.com", null)).thenReturn(UsersService.LoginStatus.ACTIVE);
        doNothing().when(users).syncEmail(anyString(), anyString());
        when(users.onboardingState("uid")).thenReturn(new UsersService.OnboardingState(false, "verification"));
        when(users.currentProfile("uid")).thenReturn(Optional.empty());

        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("uid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("email", "test@example.com")
                .claim("firebase", Map.of("sign_in_provider", "apple.com"))
                .build();

        ResponseEntity<?> response = controller.me(jwt);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) response.getBody();
        assertThat(resp).isNotNull();
        assertThat(resp.get("sign_in_provider")).isEqualTo("apple.com");
    }

    @Test
    void me_sign_in_provider_is_null_when_firebase_claim_missing() {
        UsersService users = mock(UsersService.class);
        AppConfigService appConfig = mock(AppConfigService.class);
        MeController controller = new MeController(users, appConfig);

        when(users.onLogin("uid", null, null)).thenReturn(UsersService.LoginStatus.ACTIVE);
        when(users.onboardingState("uid")).thenReturn(new UsersService.OnboardingState(false, "verification"));
        when(users.currentProfile("uid")).thenReturn(Optional.empty());

        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("uid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        ResponseEntity<?> response = controller.me(jwt);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) response.getBody();
        assertThat(resp).isNotNull();
        assertThat(resp.get("sign_in_provider")).isNull();
    }
}
