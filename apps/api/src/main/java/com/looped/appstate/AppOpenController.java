package com.looped.appstate;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class AppOpenController {
    private final AppOpenService service;

    public AppOpenController(AppOpenService service) {
        this.service = service;
    }

    @PostMapping("/v1/app/open")
    public ResponseEntity<?> open(@AuthenticationPrincipal Jwt jwt,
                                  @RequestBody(required = false) AppOpenService.AppOpenRequest body) {
        AppOpenService.AppOpenRequest req = body == null
                ? new AppOpenService.AppOpenRequest(null, null, List.of())
                : body;
        var result = service.open(jwt.getSubject(), req);
        if (result.status() == AppOpenService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        return ResponseEntity.ok(result.response());
    }
}
