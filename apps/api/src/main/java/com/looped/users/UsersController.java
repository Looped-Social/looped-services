package com.looped.users;

import com.looped.posts.PostPayloads;
import com.looped.comments.CommentsService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
            case OK -> ResponseEntity.status(HttpStatus.CREATED).body(UserPayloads.fromProfile(res.profile(), true, true));
        };
    }

    @GetMapping("/username/availability")
    public ResponseEntity<?> usernameAvailability(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("username") String username
    ) {
        var res = service.usernameAvailability(jwt.getSubject(), username);
        if (!res.valid()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_username"));
        }
        return ResponseEntity.ok(Map.of(
                "username", res.username(),
                "available", res.available(),
                "owned_by_me", res.ownedByMe()
        ));
    }

    @PutMapping("/me")
    public ResponseEntity<?> updateProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Validated @RequestBody UpdateProfileRequest body
    ) {
        boolean anonymous = body.isAnonymous() != null && body.isAnonymous();
        var res = service.updateProfile(jwt.getSubject(), body.displayName(), body.bio(), anonymous,
                body.showFollowerCount(), body.messagePermission(), body.profileMediaAssetId());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before updating profile"
            ));
            case INVALID_MESSAGE_PERMISSION -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_message_permission"
            ));
            case MEDIA_ASSET_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "media_asset_not_found"
            ));
            case MEDIA_ASSET_FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "media_asset_forbidden"
            ));
            case INVALID_PROFILE_IMAGE -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_profile_image"
            ));
            case CDN_NOT_CONFIGURED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "cdn_not_configured"
            ));
            case OK -> ResponseEntity.ok(UserPayloads.fromProfile(res.profile(), true, true));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @GetMapping("/me/content")
    public ResponseEntity<?> myContent(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.contentMe(jwt.getSubject(), cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing content"
            ));
            case OK -> {
                Map<String, Object> body = new HashMap<>();
                body.put("items", res.items());
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "not_found",
                    "message", "User not found"
            ));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "forbidden",
                    "message", "Cross-company access denied"
            ));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @PutMapping("/me/display-community")
    public ResponseEntity<?> updateDisplayCommunity(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UpdateDisplayCommunityRequest body
    ) {
        Long communityId = body == null ? null : body.communityId();
        var res = service.updateDisplayCommunity(jwt.getSubject(), communityId);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before updating profile"
            ));
            case COMMUNITY_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found"
            ));
            case COMMUNITY_NOT_VERIFIED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_not_verified",
                    "message", "You must be verified in this community"
            ));
            case OK -> ResponseEntity.ok(UserPayloads.fromProfile(res.profile(), true, true));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @PutMapping("/me/display-specialization")
    public ResponseEntity<?> updateDisplaySpecialization(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UpdateDisplaySpecializationRequest body
    ) {
        Long specializationId = body == null ? null : body.specializationId();
        var res = service.updateDisplaySpecialization(jwt.getSubject(), specializationId);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before updating profile"
            ));
            case SPECIALIZATION_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "specialization_not_found"
            ));
            case INVALID_SPECIALIZATION -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_specialization",
                    "message", "Specialization must be a major or department"
            ));
            case SPECIALIZATION_NOT_JOINED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "specialization_not_joined",
                    "message", "You must join this specialization to display it"
            ));
            case OK -> ResponseEntity.ok(UserPayloads.fromProfile(res.profile(), true, true));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @PutMapping("/me/identity")
    public ResponseEntity<?> updateIdentity(
            @AuthenticationPrincipal Jwt jwt,
            @Validated @RequestBody UpdateIdentityRequest body
    ) {
        var res = service.updateIdentity(jwt.getSubject(), body.username(), body.firstName(), body.lastName(), body.dateOfBirth());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before updating profile"
            ));
            case INVALID_USERNAME -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_username"
            ));
            case USERNAME_TAKEN -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "username_taken"
            ));
            case OK -> ResponseEntity.ok(UserPayloads.fromProfile(res.profile(), true, true));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @PutMapping("/me/onboarding")
    public ResponseEntity<?> updateOnboarding(
            @AuthenticationPrincipal Jwt jwt,
            @Validated @RequestBody UpdateOnboardingRequest body
    ) {
        var res = service.updateOnboardingStep(jwt.getSubject(), body.step());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
            case INVALID_STEP -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_onboarding_step"
            ));
            case OK -> ResponseEntity.ok(Map.of(
                    "onboarding_complete", res.state().onboardingComplete(),
                    "onboarding_step", res.state().onboardingStep()
            ));
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
        var res = service.deleteMe(jwt.getSubject(), deleteMode);
        if (res.status() == UsersService.DeleteStatus.FIREBASE_DELETE_FAILED) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "firebase_delete_failed"
            ));
        }
        if (res.status() == UsersService.DeleteStatus.FIREBASE_DELETE_SKIPPED) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "firebase_admin_not_configured"
            ));
        }
        if (deleteMode == UsersService.DeleteMode.SOFT) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(Map.of(
                "status", "deleted",
                "firebase_status", res.firebaseStatus() == null ? "unknown" : res.firebaseStatus().name().toLowerCase(java.util.Locale.ROOT),
                "firebase_deleted", res.firebaseStatus() == UsersService.FirebaseDeleteStatus.OK
        ));
    }

    @PostMapping("/me/deactivate")
    public ResponseEntity<?> deactivate(@AuthenticationPrincipal Jwt jwt) {
        service.deleteMe(jwt.getSubject(), UsersService.DeleteMode.SOFT);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/delete")
    public ResponseEntity<?> deleteAccount(@AuthenticationPrincipal Jwt jwt) {
        var res = service.deleteMe(jwt.getSubject(), UsersService.DeleteMode.HARD);
        if (res.status() == UsersService.DeleteStatus.FIREBASE_DELETE_FAILED) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "firebase_delete_failed"
            ));
        }
        if (res.status() == UsersService.DeleteStatus.FIREBASE_DELETE_SKIPPED) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "firebase_admin_not_configured"
            ));
        }
        return ResponseEntity.ok(Map.of(
                "status", "deleted",
                "firebase_status", res.firebaseStatus() == null ? "unknown" : res.firebaseStatus().name().toLowerCase(java.util.Locale.ROOT),
                "firebase_deleted", res.firebaseStatus() == UsersService.FirebaseDeleteStatus.OK
        ));
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
            case OK -> ResponseEntity.ok(UserPayloads.fromProfile(res.profile(), res.includeFollowerCounts()));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
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
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<?> content(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.content(jwt.getSubject(), id, cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing content"
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
                Map<String, Object> body = new HashMap<>();
                body.put("items", res.items());
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
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
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    public record UpdateProfileRequest(
            @Size(max = 100) String displayName,
            @Size(max = 500) String bio,
            @NotNull Boolean isAnonymous,
            Boolean showFollowerCount,
            String messagePermission,
            @Positive Long profileMediaAssetId
    ) {}

    public record UpdateDisplayCommunityRequest(Long communityId) {}

    public record UpdateDisplaySpecializationRequest(Long specializationId) {}

    public record UpdateIdentityRequest(
            @NotBlank @Size(min = 3, max = 30) String username,
            @NotBlank @Size(max = 50) String firstName,
            @NotBlank @Size(max = 50) String lastName,
            @NotNull LocalDate dateOfBirth
    ) {}

    public record UpdateOnboardingRequest(@NotBlank String step) {}

    public record OnboardRequest(
            @NotBlank @Size(min = 3, max = 30) String username,
            @NotBlank @Size(max = 50) String firstName,
            @NotBlank @Size(max = 50) String lastName,
            @NotNull LocalDate dateOfBirth
    ) {}

    public record DeleteRequest(Boolean confirm, String password) {}
}
