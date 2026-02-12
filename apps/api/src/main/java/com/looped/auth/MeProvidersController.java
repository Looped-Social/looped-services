package com.looped.auth;

import com.looped.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(MeProvidersController.class);

    private static final String PROVIDER_APPLE = "apple.com";
    private static final String PROVIDER_GOOGLE = "google.com";

    private final FirebaseAdminService firebaseAdmin;
    private final UserRepository users;

    public MeProvidersController(FirebaseAdminService firebaseAdmin, UserRepository users) {
        this.firebaseAdmin = firebaseAdmin;
        this.users = users;
    }

    @DeleteMapping("/apple")
    public ResponseEntity<?> unlinkApple(@AuthenticationPrincipal Jwt jwt) {
        return unlink(jwt, PROVIDER_APPLE);
    }

    @DeleteMapping("/google")
    public ResponseEntity<?> unlinkGoogle(@AuthenticationPrincipal Jwt jwt) {
        return unlink(jwt, PROVIDER_GOOGLE);
    }

    private ResponseEntity<?> unlink(Jwt jwt, String providerId) {
        String firebaseUid = jwt.getSubject();
        var status = users.accessStatusByFirebaseUid(firebaseUid);
        if (status.isEmpty()) {
            log.warn("provider_unlink_blocked backend_user_missing uid={} provider={}", firebaseUid, providerId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "account_not_actionable",
                    "reason", "backend_user_missing"
            ));
        }
        if (status.get().deletedAt != null) {
            log.warn("provider_unlink_blocked account_deleted uid={} user_id={} provider={}",
                    firebaseUid, status.get().id, providerId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "account_not_actionable",
                    "reason", "account_deleted"
            ));
        }
        if (status.get().disabledAt != null) {
            log.warn("provider_unlink_blocked account_disabled uid={} user_id={} provider={}",
                    firebaseUid, status.get().id, providerId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "account_disabled"
            ));
        }

        var res = firebaseAdmin.unlinkProvider(firebaseUid, providerId);
        return switch (res.status()) {
            case OK -> ResponseEntity.ok(Map.of(
                    "provider", providerId,
                    "unlinked", res.unlinked()
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "account_not_actionable",
                    "reason", "firebase_user_not_found",
                    "code", res.error()
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
