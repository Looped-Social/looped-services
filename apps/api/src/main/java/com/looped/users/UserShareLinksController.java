package com.looped.users;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/v1/users")
public class UserShareLinksController {
    private final UserShareLinksService shareLinks;

    public UserShareLinksController(UserShareLinksService shareLinks) {
        this.shareLinks = shareLinks;
    }

    @GetMapping("/slug/availability")
    public ResponseEntity<?> availability(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("slug") String slug
    ) {
        var res = shareLinks.availability(jwt.getSubject(), slug);
        if (res.status() == UserShareLinksService.Status.SLUG_INVALID) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "slug_invalid"
            ));
        }
        return ResponseEntity.ok(Map.of(
                "slug", res.slug(),
                "available", res.available(),
                "ownedByMe", res.ownedByMe(),
                "owned_by_me", res.ownedByMe(),
                "reserved", res.reserved()
        ));
    }

    @GetMapping("/me/share-link")
    public ResponseEntity<?> getMyShareLink(@AuthenticationPrincipal Jwt jwt) {
        var res = shareLinks.mySettings(jwt.getSubject());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case OK -> ResponseEntity.ok(res.settings());
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @GetMapping("/{id}/share-link")
    public ResponseEntity<?> getUserShareLink(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id
    ) {
        var res = shareLinks.settingsForUser(jwt.getSubject(), id);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "User not found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Cross-company access denied"
            ));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "error", "profile_unavailable",
                    "message", "Profile is unavailable"
            ));
            case OK -> {
                Map<String, Object> body = new HashMap<>();
                body.put("user_id", id);
                body.put("userId", id);
                body.putAll(res.settings());
                yield ResponseEntity.ok(body);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @PutMapping("/me/share-link")
    public ResponseEntity<?> updateMyShareLink(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) UpdateShareLinkRequest body
    ) {
        String customSlug = body == null ? null : body.customSlug();
        var res = shareLinks.updateCustomSlug(jwt.getSubject(), customSlug);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case SLUG_INVALID -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "slug_invalid"
            ));
            case SLUG_RESERVED -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "slug_reserved"
            ));
            case SLUG_TAKEN -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "slug_taken"
            ));
            case SLUG_NOT_ACTIONABLE -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "slug_not_actionable"
            ));
            case OK -> ResponseEntity.ok(res.settings());
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    public record UpdateShareLinkRequest(String customSlug) {}
}
