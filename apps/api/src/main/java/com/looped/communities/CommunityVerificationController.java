package com.looped.communities;

import com.looped.users.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/communities")
public class CommunityVerificationController {
    private final CommunityVerificationService service;
    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository verifications;

    public CommunityVerificationController(CommunityVerificationService service,
                                           UserRepository users,
                                           CommunitiesRepository communities,
                                           CommunityVerificationsRepository verifications) {
        this.service = service;
        this.users = users;
        this.communities = communities;
        this.verifications = verifications;
    }

    @PostMapping("/{communityId}/verification/start")
    public ResponseEntity<?> start(@AuthenticationPrincipal Jwt jwt,
                                   @PathVariable("communityId") long communityId,
                                   @Valid @RequestBody StartRequest body) {
        var res = service.start(jwt.getSubject(), communityId, body.method(), body.email());
        if (res.status() == CommunityVerificationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == CommunityVerificationService.Status.COMMUNITY_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        if (res.status() == CommunityVerificationService.Status.BAD_REQUEST) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", res.error()));
        }
        if (res.status() == CommunityVerificationService.Status.CONFLICT) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", res.error()));
        }
        if (res.status() == CommunityVerificationService.Status.SEND_FAILED) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", res.error()));
        }
        if (res.status() != CommunityVerificationService.Status.OK) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        Map<String, Object> out = new HashMap<>();
        out.put("status", "pending");
        out.put("method", res.method());
        if (res.devCode() != null) out.put("dev_code", res.devCode());
        if (res.sessionId() != null) out.put("session_id", res.sessionId());
        if (res.instructions() != null) out.put("instructions", res.instructions());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{communityId}/verification/finish")
    public ResponseEntity<?> finish(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable("communityId") long communityId,
                                    @Valid @RequestBody FinishRequest body) {
        String email = jwt.getClaimAsString("email");
        var res = service.finish(jwt.getSubject(), communityId, body.email(), email, body.method(), body.code(), body.mediaKey(), body.token());
        if (res.status() == CommunityVerificationService.Status.USER_NOT_PROVISIONED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (res.status() == CommunityVerificationService.Status.COMMUNITY_NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        if (res.status() == CommunityVerificationService.Status.BAD_REQUEST) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", res.error()));
        }
        if (res.status() == CommunityVerificationService.Status.INVALID_CODE) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "invalid_code"));
        }
        if (res.status() == CommunityVerificationService.Status.CONFLICT) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", res.error()));
        }
        boolean verified = res.verified() != null && res.verified();
        Map<String, Object> out = new HashMap<>();
        out.put("verified", verified);
        out.put("status", verified ? "approved" : "pending");
        if (res.expiresAt() != null) out.put("expires_at", res.expiresAt());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/verifications")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt) {
        var actor = users.findByFirebaseUid(jwt.getSubject());
        if (actor.isEmpty() || actor.get().companyId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<Map<String, Object>> items = verifications.listForUser(actor.get().id).stream().map(row -> {
            Map<String, Object> item = new HashMap<>();
            item.put("community_id", row.communityId);
            item.put("community_name", row.communityName);
            item.put("community_kind", row.communityKind);
            item.put("method", row.method);
            item.put("verified", row.verified);
            item.put("verified_at", row.verifiedAt);
            item.put("expires_at", row.expiresAt);
            boolean active = row.verified && (row.expiresAt == null || row.expiresAt.isAfter(now));
            item.put("active", active);
            return item;
        }).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    @GetMapping("/{communityId}/permissions")
    public ResponseEntity<?> permissions(@AuthenticationPrincipal Jwt jwt,
                                         @PathVariable("communityId") long communityId) {
        var actor = users.findByFirebaseUid(jwt.getSubject());
        if (actor.isEmpty() || actor.get().companyId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        var community = communities.findById(communityId);
        if (community.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        boolean requiresVerification = !"specialization".equalsIgnoreCase(community.get().kind);
        boolean canPost = !requiresVerification || verifications.isVerified(actor.get().id, communityId);
        return ResponseEntity.ok(Map.of(
                "can_post", canPost,
                "requires_verification", requiresVerification
        ));
    }

    @DeleteMapping("/{communityId}/verification")
    public ResponseEntity<?> unverify(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable("communityId") long communityId) {
        var actor = users.findByFirebaseUid(jwt.getSubject());
        if (actor.isEmpty() || actor.get().companyId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "user_not_provisioned"));
        }
        if (communities.findById(communityId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        verifications.unverifyAndReleaseEmail(actor.get().id, communityId);
        if (actor.get().displayCommunityId != null && actor.get().displayCommunityId == communityId) {
            users.updateDisplayCommunity(actor.get().id, null);
        }
        return ResponseEntity.ok(Map.of("status", "unverified"));
    }

    public record StartRequest(@NotBlank String method, String email) {}
    public record FinishRequest(@NotBlank String method, String code, String mediaKey, String token, String email) {}
}
