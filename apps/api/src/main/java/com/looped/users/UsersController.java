package com.looped.users;

import com.looped.posts.PostPayloads;
import com.looped.comments.CommentsService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/users")
public class UsersController {
    private final UsersService service;
    private final CommentsService commentsService;

    public UsersController(UsersService service, CommentsService commentsService) {
        this.service = service;
        this.commentsService = commentsService;
    }

    @PostMapping("/onboard")
    public ResponseEntity<?> onboard(
            @AuthenticationPrincipal Jwt jwt,
            @Validated @RequestBody OnboardRequest body
    ) {
        String email = jwt.getClaimAsString("email");
        var res = service.onboard(jwt.getSubject(), email, body.username(), body.firstName(), body.lastName(), body.dateOfBirth());
        return switch (res.status()) {
            case BAD_REQUEST -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", res.error()));
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", res.error()));
            case OK -> ResponseEntity.status(HttpStatus.CREATED).body(UserPayloads.fromProfile(res.profile()));
        };
    }

    @GetMapping("/username/availability")
    public ResponseEntity<?> usernameAvailability(@RequestParam("username") String username) {
        var res = service.usernameAvailability(username);
        if (!res.valid()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_username"));
        }
        return ResponseEntity.ok(Map.of(
                "username", res.username(),
                "available", res.available()
        ));
    }

    @PutMapping("/me")
    public ResponseEntity<?> updateProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Validated @RequestBody UpdateProfileRequest body
    ) {
        boolean anonymous = body.isAnonymous() != null && body.isAnonymous();
        var res = service.updateProfile(jwt.getSubject(), body.displayName(), body.bio(), anonymous, body.showFollowerCount());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before updating profile"
            ));
            case OK -> ResponseEntity.ok(UserPayloads.fromProfile(res.profile()));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @DeleteMapping("/me")
    public ResponseEntity<?> deleteMe(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "mode", required = false, defaultValue = "hard") String mode,
            @RequestBody(required = false) DeleteRequest body
    ) {
        UsersService.DeleteMode deleteMode = switch (mode.toLowerCase()) {
            case "hard" -> UsersService.DeleteMode.HARD;
            case "soft" -> UsersService.DeleteMode.SOFT;
            default -> null;
        };
        if (deleteMode == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_mode",
                    "message", "mode must be hard or soft"
            ));
        }
        service.deleteMe(jwt.getSubject(), deleteMode);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/deactivate")
    public ResponseEntity<?> deactivate(@AuthenticationPrincipal Jwt jwt) {
        service.deleteMe(jwt.getSubject(), UsersService.DeleteMode.SOFT);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/delete")
    public ResponseEntity<?> deleteAccount(@AuthenticationPrincipal Jwt jwt) {
        service.deleteMe(jwt.getSubject(), UsersService.DeleteMode.HARD);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/replies")
    public ResponseEntity<?> replies(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @RequestParam(value = "asAnon", required = false) Boolean asAnon,
            @RequestParam(value = "anonProfileId", required = false) Long anonProfileId,
            @RequestParam(value = "anonCert", required = false) String anonCert,
            @RequestParam(value = "anonCertKid", required = false) String anonCertKid,
            @RequestParam(value = "anonSig", required = false) String anonSig
    ) {
        boolean isAnon = Boolean.TRUE.equals(asAnon);
        if (isAnon && !hasAnonProof(asAnon, anonProfileId, anonCert, anonCertKid, anonSig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
        if (isAnon && jwt != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        if (!isAnon && jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = commentsService.userReplies(jwt == null ? null : jwt.getSubject(), id, cursor, lim,
                toAnonProof(asAnon, anonProfileId, anonCert, anonCertKid, anonSig));
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case USER_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found"
            ));
            case INVALID_ANON_PROOF -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> {
                List<Map<String, Object>> items = res.comments().stream().map(com.looped.comments.CommentPayloads::from).toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    private com.looped.anon.AnonProofService.AnonActionProof toAnonProof(Boolean asAnon, Long anonProfileId, String anonCert, String anonCertKid, String anonSig) {
        if (asAnon == null || !asAnon) return null;
        return new com.looped.anon.AnonProofService.AnonActionProof(anonProfileId, anonCert, anonCertKid, anonSig);
    }

    private boolean hasAnonProof(Boolean asAnon, Long anonProfileId, String anonCert, String anonCertKid, String anonSig) {
        if (asAnon == null || !asAnon) return false;
        return anonProfileId != null
                && anonCert != null && !anonCert.isBlank()
                && anonCertKid != null && !anonCertKid.isBlank()
                && anonSig != null && !anonSig.isBlank();
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("query") String query,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "query_required",
                    "message", "query must be provided"
            ));
        }
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.search(jwt.getSubject(), query, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case OK -> {
                List<Map<String, Object>> items = res.users().stream().map(UserPayloads::directory).toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @GetMapping
    public ResponseEntity<?> directory(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.directory(jwt.getSubject(), cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case OK -> {
                List<Map<String, Object>> items = res.users().stream().map(UserPayloads::directory).toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
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
            case OK -> ResponseEntity.ok(UserPayloads.fromProfile(res.profile()));
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

    @GetMapping("/{id}/comments")
    public ResponseEntity<?> comments(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.comments(jwt.getSubject(), id, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden"
            ));
            case OK -> {
                List<Map<String, Object>> items = res.comments().stream().map(UserPayloads::comment).toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
        };
    }

    public record UpdateProfileRequest(
            @Size(max = 100) String displayName,
            @Size(max = 500) String bio,
            @NotNull Boolean isAnonymous,
            Boolean showFollowerCount
    ) {}

    public record OnboardRequest(
            @NotBlank @Size(min = 3, max = 30) String username,
            @NotBlank @Size(max = 50) String firstName,
            @NotBlank @Size(max = 50) String lastName,
            @NotNull LocalDate dateOfBirth
    ) {}

    public record DeleteRequest(Boolean confirm, String password) {}
}
