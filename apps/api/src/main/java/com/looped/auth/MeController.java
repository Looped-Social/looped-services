package com.looped.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class MeController {

    @GetMapping("/v1/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("sub", jwt.getSubject());
        resp.put("iss", jwt.getIssuer() != null ? jwt.getIssuer().toString() : null);
        resp.put("aud", jwt.getAudience());
        Object email = jwt.getClaims().get("email");
        if (email != null) {
            resp.put("email", email);
        }
        return resp;
    }
}

