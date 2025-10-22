package com.looped.devices;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/devices")
public class DevicesController {
    private final DeviceService service;

    public DevicesController(DeviceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> register(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
            @Validated @RequestBody RegisterRequest body
    ) {
        // Idempotency-Key reserved for future Redis implementation; functional idempotency by unique apns_token
        var result = service.register(jwt.getSubject(), body.apnsToken(), body.platform());
        return switch (result.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before registering a device"
            ));
            case OK -> {
                var resp = Map.of(
                        "id", result.id(),
                        "apns_token", body.apnsToken(),
                        "platform", body.platform()
                );
                yield new ResponseEntity<>(resp, result.created() ? HttpStatus.CREATED : HttpStatus.OK);
            }
        };
    }

    public record RegisterRequest(@NotBlank String apnsToken, @NotBlank String platform) {}
}

