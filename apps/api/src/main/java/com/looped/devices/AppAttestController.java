package com.looped.devices;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/devices/app-attest")
@Validated
public class AppAttestController {
    private final AppAttestService service;

    public AppAttestController(AppAttestService service) {
        this.service = service;
    }

    @PostMapping("/challenge")
    public ResponseEntity<?> challenge(@AuthenticationPrincipal Jwt jwt) {
        var result = service.start(jwt.getSubject());
        return switch (result.status()) {
            case OK -> ResponseEntity.ok(Map.of(
                    "mode", result.mode().name().toLowerCase(java.util.Locale.ROOT),
                    "required_for_anon_enrollment", result.mode() == AppAttestProperties.Mode.ENFORCE,
                    "challenge_id", result.challengeId(),
                    "challenge", result.challenge(),
                    "expires_at", result.expiresAt()
            ));
            case DISABLED -> ResponseEntity.ok(Map.of(
                    "mode", result.mode().name().toLowerCase(java.util.Locale.ROOT),
                    "required_for_anon_enrollment", false,
                    "enabled", false
            ));
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before requesting App Attest challenge"
            ));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @PostMapping("/complete")
    public ResponseEntity<?> complete(@AuthenticationPrincipal Jwt jwt,
                                      @Valid @RequestBody CompleteRequest body) {
        var result = service.complete(
                jwt.getSubject(),
                body.challengeId(),
                body.keyId(),
                body.attestationObject(),
                body.assertionObject()
        );
        return switch (result.status()) {
            case OK -> ResponseEntity.ok(completePayload(result));
            case DISABLED -> ResponseEntity.ok(Map.of(
                    "mode", result.mode().name().toLowerCase(java.util.Locale.ROOT),
                    "required_for_anon_enrollment", false,
                    "enabled", false
            ));
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before completing App Attest"
            ));
            case INVALID_CHALLENGE -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "invalid_challenge",
                    "message", "App Attest challenge is invalid or expired"
            ));
            case INVALID_INPUT -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", result.error()
            ));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(value = "key_id", required = false) String keyId) {
        var result = service.status(jwt.getSubject(), keyId);
        return switch (result.status()) {
            case OK, MISSING -> ResponseEntity.ok(statusPayload(result));
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before checking App Attest status"
            ));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    private Map<String, Object> completePayload(AppAttestService.CompleteResult result) {
        Map<String, Object> body = new HashMap<>();
        body.put("mode", result.mode().name().toLowerCase(java.util.Locale.ROOT));
        body.put("required_for_anon_enrollment", result.requiredForAnonEnrollment());
        body.put("trusted", result.trusted());
        body.put("status", result.row() == null ? "missing" : result.row().status);
        if (result.row() != null) {
            body.put("key_id", result.row().keyId);
            body.put("trusted_until", result.row().trustedUntil);
            body.put("last_verified_at", result.row().lastVerifiedAt);
            body.put("last_error", result.row().lastError);
        }
        return body;
    }

    private Map<String, Object> statusPayload(AppAttestService.StatusResult result) {
        Map<String, Object> body = new HashMap<>();
        body.put("mode", result.mode().name().toLowerCase(java.util.Locale.ROOT));
        body.put("required_for_anon_enrollment", result.requiredForAnonEnrollment());
        body.put("trusted", result.trusted());
        body.put("key_id", result.keyId());
        if (result.row() == null) {
            body.put("status", "missing");
            return body;
        }
        body.put("status", result.row().status);
        body.put("trusted_until", result.row().trustedUntil);
        body.put("last_verified_at", result.row().lastVerifiedAt);
        body.put("last_challenge_at", result.row().lastChallengeAt);
        body.put("last_seen_at", result.row().lastSeenAt);
        body.put("last_error", result.row().lastError);
        return body;
    }

    public record CompleteRequest(
            @JsonAlias("challenge_id") @NotBlank String challengeId,
            @JsonAlias("key_id") @NotBlank String keyId,
            @JsonAlias("attestation_object") @NotBlank String attestationObject,
            @JsonAlias("assertion_object") String assertionObject
    ) {}
}
