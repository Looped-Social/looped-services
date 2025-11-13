package com.looped.users;

import com.looped.posts.PostPayloads;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/users")
public class UsersController {
    private final UsersService service;

    public UsersController(UsersService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> profile(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
        var res = service.profile(jwt.getSubject(), id);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing profiles"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "User not found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Cross-company access denied"
            ));
            case OK -> ResponseEntity.ok(toProfileMap(res.profile()));
        };
    }

    @GetMapping("/{id}/posts")
    public ResponseEntity<?> userPosts(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.posts(jwt.getSubject(), id, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing posts"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "User not found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Cross-company access denied"
            ));
            case OK -> {
                List<Map<String, Object>> items = res.posts().stream().map(PostPayloads::from).toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) {
                    body.put("next_cursor", res.nextCursor());
                }
                yield ResponseEntity.ok(body);
            }
        };
    }

    private Map<String, Object> toProfileMap(UsersService.UserProfile profile) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", profile.id());
        map.put("handle", profile.handle());
        map.put("company_id", profile.companyId());
        map.put("created_at", profile.createdAt());
        if (profile.verification() != null) {
            Map<String, Object> verification = new HashMap<>();
            verification.put("method", profile.verification().method());
            verification.put("verified", profile.verification().verified());
            verification.put("verified_at", profile.verification().verifiedAt());
            map.put("verification", verification);
        }
        return map;
    }
}

