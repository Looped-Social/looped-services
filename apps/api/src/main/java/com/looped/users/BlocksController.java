package com.looped.users;

import com.looped.principals.PrincipalPayloads;
import com.looped.settings.AppConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/users")
public class BlocksController {
    private final BlocksService service;
    private final AppConfigService appConfig;

    public BlocksController(BlocksService service, AppConfigService appConfig) {
        this.service = service;
        this.appConfig = appConfig;
    }

    @GetMapping("/blocked")
    public ResponseEntity<?> blocked(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        var res = service.blocked(jwt.getSubject(), cursor, lim);
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before viewing blocked users"
            ));
            case OK -> {
                String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
                List<Map<String, Object>> items = res.users().stream()
                        .map(row -> PrincipalPayloads.directory(row, defaultProfileImageUrl))
                        .toList();
                Map<String, Object> body = new HashMap<>();
                body.put("items", items);
                if (res.nextCursor() != null) body.put("next_cursor", res.nextCursor());
                yield ResponseEntity.ok(body);
            }
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @PostMapping("/{id}/block")
    public ResponseEntity<?> block(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) BlockRequest body
    ) {
        boolean asAnon = body != null && Boolean.TRUE.equals(body.asAnon());
        if (asAnon && (body == null || !body.hasAnonProof())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
        if (asAnon && jwt != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        if (!asAnon && jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        var res = service.blockUser(jwt == null ? null : jwt.getSubject(), id, body == null ? null : body.toAnonProof());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before blocking users"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case INVALID_TARGET -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_target",
                    "message", "Cannot block this user"
            ));
            case INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> new ResponseEntity<>(Map.of(
                    "user_id", id,
                    "blocked", true
            ), res.changed() ? HttpStatus.CREATED : HttpStatus.OK);
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    @DeleteMapping("/{id}/block")
    public ResponseEntity<?> unblock(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") long id,
            @RequestBody(required = false) BlockRequest body
    ) {
        boolean asAnon = body != null && Boolean.TRUE.equals(body.asAnon());
        if (asAnon && (body == null || !body.hasAnonProof())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
        if (asAnon && jwt != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        if (!asAnon && jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authorization is required"
            ));
        }
        var res = service.unblockUser(jwt == null ? null : jwt.getSubject(), id, body == null ? null : body.toAnonProof());
        return switch (res.status()) {
            case USER_NOT_PROVISIONED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before unblocking users"
            ));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
            case INVALID_TARGET -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "invalid_target",
                    "message", "Cannot unblock this user"
            ));
            case INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
            case OK -> ResponseEntity.ok(Map.of(
                    "user_id", id,
                    "blocked", false
            ));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        };
    }

    public record BlockRequest(Boolean asAnon, Long anonProfileId, String anonCert, String anonCertKid, String anonSig) {
        com.looped.anon.AnonProofService.AnonActionProof toAnonProof() {
            if (asAnon == null || !asAnon) return null;
            return new com.looped.anon.AnonProofService.AnonActionProof(anonProfileId, anonCert, anonCertKid, anonSig);
        }

        boolean hasAnonProof() {
            if (asAnon == null || !asAnon) return false;
            return anonProfileId != null
                    && anonCert != null && !anonCert.isBlank()
                    && anonCertKid != null && !anonCertKid.isBlank()
                    && anonSig != null && !anonSig.isBlank();
        }
    }
}
