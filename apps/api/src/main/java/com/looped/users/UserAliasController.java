package com.looped.users;

import com.looped.verification.VerificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@Validated
public class UserAliasController {
    private final UsersService users;
    private final VerificationService verificationService;

    public UserAliasController(UsersService users, VerificationService verificationService) {
        this.users = users;
        this.verificationService = verificationService;
    }

    @PutMapping("/users/me")
    public ResponseEntity<?> updateProfileAlias(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UsersController.UpdateProfileRequest body
    ) {
        boolean anonymous = body.isAnonymous() != null && body.isAnonymous();
        var res = users.updateProfile(jwt.getSubject(), body.displayName(), body.bio(), anonymous);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before updating profile"
            ));
            case OK -> ResponseEntity.ok(UserPayloads.fromProfile(res.profile()));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @PostMapping({"/users/verify-employment", "/v1/users/verify-employment"})
    public ResponseEntity<?> verifyEmployment(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody VerificationAliasRequest body
    ) {
        String method = (body.method() == null || body.method().isBlank()) ? "email" : body.method();
        var res = verificationService.start(jwt.getSubject(), method);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
            case BAD_REQUEST -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", res.error()));
            case OK -> {
                Map<String, Object> out = new HashMap<>();
                out.put("status", "pending");
                out.put("method", res.method());
                if (res.devCode() != null) out.put("dev_code", res.devCode());
                if (res.sessionId() != null) out.put("session_id", res.sessionId());
                if (res.instructions() != null) out.put("instructions", res.instructions());
                yield ResponseEntity.ok(out);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    public record VerificationAliasRequest(String method) {}
}
