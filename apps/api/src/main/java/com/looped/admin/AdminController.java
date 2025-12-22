package com.looped.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin")
public class AdminController {
    private final AdminAuthService auth;

    public AdminController(AdminAuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        var res = auth.check(jwt.getSubject(), email);
        if (res.status() == AdminAuthService.Status.NOT_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        var admin = res.admin();
        Map<String, Object> body = new HashMap<>();
        body.put("id", admin.id);
        body.put("role", admin.role);
        body.put("status", admin.status);
        body.put("permissions", admin.permissions);
        if (admin.email != null) body.put("email", admin.email);
        return ResponseEntity.ok(body);
    }
}
