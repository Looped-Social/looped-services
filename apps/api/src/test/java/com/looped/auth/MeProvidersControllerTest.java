package com.looped.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeProvidersControllerTest {

    @Test
    void unlink_apple_returns_ok_when_unlinked() {
        FirebaseAdminService firebaseAdmin = mock(FirebaseAdminService.class);
        when(firebaseAdmin.unlinkProvider("uid", "apple.com"))
                .thenReturn(FirebaseAdminService.UnlinkProviderResult.ok(true));
        MeProvidersController controller = new MeProvidersController(firebaseAdmin);

        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("uid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        ResponseEntity<?> resp = controller.unlinkApple(jwt);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(Map.of("provider", "apple.com", "unlinked", true));
    }

    @Test
    void unlink_apple_returns_503_when_firebase_admin_not_configured() {
        FirebaseAdminService firebaseAdmin = mock(FirebaseAdminService.class);
        when(firebaseAdmin.unlinkProvider("uid", "apple.com"))
                .thenReturn(FirebaseAdminService.UnlinkProviderResult.skipped("not_configured"));
        MeProvidersController controller = new MeProvidersController(firebaseAdmin);

        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("uid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        ResponseEntity<?> resp = controller.unlinkApple(jwt);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody()).isEqualTo(Map.of("error", "firebase_admin_not_configured"));
    }
}

