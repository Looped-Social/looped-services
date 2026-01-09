package com.looped.content;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/content/preferences")
public class ContentPreferencesController {
    private final ContentPreferencesService service;

    public ContentPreferencesController(ContentPreferencesService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt) {
        var res = service.get(jwt.getSubject());
        if (res.status() == ContentPreferencesService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        return ResponseEntity.ok(Map.of(
                "content", Map.of("hide_anonymous_posts", res.hideAnonymousPosts())
        ));
    }

    @PutMapping
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) ContentPreferencesUpdate body) {
        if (body == null || body.hideAnonymousPosts() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_body"));
        }
        var res = service.update(jwt.getSubject(), body.hideAnonymousPosts());
        if (res.status() == ContentPreferencesService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        return ResponseEntity.ok(Map.of(
                "content", Map.of("hide_anonymous_posts", res.hideAnonymousPosts())
        ));
    }

    public record ContentPreferencesUpdate(Boolean hideAnonymousPosts) {}
}

