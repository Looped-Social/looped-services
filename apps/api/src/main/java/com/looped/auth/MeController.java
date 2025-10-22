package com.looped.auth;

import com.looped.users.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class MeController {

    private final UserRepository users;

    public MeController(UserRepository users) {
        this.users = users;
    }

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

        var existing = users.findByFirebaseUid(jwt.getSubject());
        if (existing.isPresent()) {
            var u = existing.get();
            resp.put("provisioned", true);
            Map<String, Object> user = new HashMap<>();
            user.put("id", u.id);
            if (u.handle != null) user.put("handle", u.handle);
            if (u.companyId != null) user.put("company_id", u.companyId);
            resp.put("user", user);
        } else {
            resp.put("provisioned", false);
        }
        return resp;
    }
}
