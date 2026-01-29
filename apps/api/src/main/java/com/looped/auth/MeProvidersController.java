package com.looped.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/me/providers")
public class MeProvidersController {

    private static final String PROVIDER_APPLE = "apple.com";

    private final FirebaseAdminService firebaseAdmin;

    public MeProvidersController(FirebaseAdminService firebaseAdmin) {
        this.firebaseAdmin = firebaseAdmin;
    }

    @DeleteMapping("/apple")
    public ResponseEntity<?> unlinkApple(@AuthenticationPrincipal Jwt jwt) {
        var res = firebaseAdmin.unlinkProvider(jwt.getSubject(), PROVIDER_APPLE);
        return switch (res.status()) {
            case OK -> ResponseEntity.ok(Map.of(
                    "provider", PROVIDER_APPLE,
                    "unlinked", res.unlinked()
            ));
            case SKIPPED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "firebase_admin_not_configured"
            ));
            case FAILED -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "firebase_admin_error",
                    "code", res.error()
            ));
        };
    }
}

