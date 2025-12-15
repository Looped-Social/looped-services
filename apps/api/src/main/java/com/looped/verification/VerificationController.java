package com.looped.verification;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/verification")
public class VerificationController {
    private final VerificationService service;

    public VerificationController(VerificationService service) {
        this.service = service;
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@AuthenticationPrincipal Jwt jwt, @Validated @RequestBody StartRequest body) {
        var res = service.start(jwt.getSubject(), body.method());
        if (res.status() == VerificationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == VerificationService.Status.BAD_REQUEST) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", res.error()));
        }
        if (res.status() != VerificationService.Status.OK) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        Map<String, Object> out = new HashMap<>();
        out.put("status", "pending");
        out.put("method", res.method());
        if (res.devCode() != null) out.put("dev_code", res.devCode());
        if (res.sessionId() != null) out.put("session_id", res.sessionId());
        if (res.instructions() != null) out.put("instructions", res.instructions());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/finish")
    public ResponseEntity<?> finish(@AuthenticationPrincipal Jwt jwt, @Validated @RequestBody FinishRequest body) {
        var res = service.finish(jwt.getSubject(), body.method(), body.code(), body.mediaKey(), body.token());
        if (res.status() == VerificationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == VerificationService.Status.BAD_REQUEST) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", res.error()));
        }
        if (res.status() == VerificationService.Status.INVALID_CODE) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "invalid_code"));
        }
        return ResponseEntity.ok(Map.of("verified", true));
    }

    public record StartRequest(@NotBlank String method) {}
    public record FinishRequest(@NotBlank String method, String code, String mediaKey, String token) {}
}
