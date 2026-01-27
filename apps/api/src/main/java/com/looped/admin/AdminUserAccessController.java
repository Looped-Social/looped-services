package com.looped.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.SpecializationJoinsRepository;
import com.looped.communities.SpecializationLimitsRepository;
import com.looped.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin")
public class AdminUserAccessController {
    private static final String SPECIALIZATION_LIMIT_SCOPE = "join";

    private final AdminAuthService auth;
    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final SpecializationJoinsRepository specializationJoins;
    private final SpecializationLimitsRepository specializationLimits;
    private final AdminAuditRepository audit;

    public AdminUserAccessController(AdminAuthService auth,
                                     UserRepository users,
                                     CommunitiesRepository communities,
                                     CommunityVerificationsRepository communityVerifications,
                                     SpecializationJoinsRepository specializationJoins,
                                     SpecializationLimitsRepository specializationLimits,
                                     AdminAuditRepository audit) {
        this.auth = auth;
        this.users = users;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.specializationJoins = specializationJoins;
        this.specializationLimits = specializationLimits;
        this.audit = audit;
    }

    @GetMapping("/users/{id}/verified-communities")
    public ResponseEntity<?> verifiedCommunities(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long userId) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VERIFY_USERS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (users.findByIdIncludingDeleted(userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "user_not_found"));
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<Map<String, Object>> items = communityVerifications.listForUser(userId).stream()
                .filter(r -> r.verified && (r.expiresAt == null || r.expiresAt.isAfter(now)))
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("community_id", r.communityId);
                    m.put("community_name", r.communityName);
                    m.put("community_kind", r.communityKind);
                    m.put("method", r.method);
                    m.put("verified_at", r.verifiedAt);
                    m.put("expires_at", r.expiresAt);
                    return m;
                })
                .toList();
        return ResponseEntity.ok(Map.of(
                "user_id", userId,
                "items", items
        ));
    }

    @PostMapping("/users/{id}/verified-communities/{communityId}/revoke")
    public ResponseEntity<?> revokeVerifiedCommunityAlias(@AuthenticationPrincipal Jwt jwt,
                                                          @PathVariable("id") long userId,
                                                          @PathVariable("communityId") long communityId,
                                                          @RequestBody(required = false) RevokeRequest body) {
        return revokeCommunityVerification(jwt, userId, communityId, body);
    }

    @PostMapping("/users/{id}/community-verifications/{communityId}/revoke")
    public ResponseEntity<?> revokeCommunityVerification(@AuthenticationPrincipal Jwt jwt,
                                                         @PathVariable("id") long userId,
                                                         @PathVariable("communityId") long communityId,
                                                         @RequestBody(required = false) RevokeRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VERIFY_USERS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (users.findByIdIncludingDeleted(userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "user_not_found"));
        }
        if (communities.findById(communityId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }

        var existing = communityVerifications.findForUserAndCommunity(userId, communityId);
        if (existing.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_verification_not_found"));
        }

        String method = existing.get().method != null && !existing.get().method.isBlank()
                ? existing.get().method
                : "manual";
        communityVerifications.markUnverified(userId, communityId, method);
        String reason = body != null ? body.reason() : null;
        audit.log(authRes.admin().id, "community_verification.revoke", "community_verification", communityId,
                "user_id=" + userId + (reason != null && !reason.isBlank() ? " reason=" + reason : ""));

        return ResponseEntity.ok(Map.of(
                "status", "revoked",
                "user_id", userId,
                "community_id", communityId
        ));
    }

    @PostMapping("/users/{id}/specializations/{specializationType}/reset-cooldown")
    public ResponseEntity<?> resetSpecializationCooldownAlias(@AuthenticationPrincipal Jwt jwt,
                                                              @PathVariable("id") long userId,
                                                              @PathVariable("specializationType") String specializationType) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VERIFY_USERS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (users.findByIdIncludingDeleted(userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "user_not_found"));
        }
        String normalizedType = normalizeType(specializationType);
        if (normalizedType == null || "all".equals(normalizedType)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_specialization_type",
                    "message", "specializationType must be major or field"
            ));
        }
        int clearedCooldowns = specializationLimits.deleteLastChange(userId, normalizedType, SPECIALIZATION_LIMIT_SCOPE) ? 1 : 0;
        audit.log(authRes.admin().id, "specialization_join_limits.reset", "user", userId,
                "specialization_type=" + normalizedType + " clear_joins=false");
        return ResponseEntity.ok(Map.of(
                "status", "reset",
                "user_id", userId,
                "specialization_type", normalizedType,
                "cooldowns_cleared", clearedCooldowns
        ));
    }

    @PostMapping("/users/{id}/specializations/join-limits/reset")
    public ResponseEntity<?> resetSpecializationJoinLimits(@AuthenticationPrincipal Jwt jwt,
                                                           @PathVariable("id") long userId,
                                                           @RequestBody(required = false) ResetJoinLimitsRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.VERIFY_USERS);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (users.findByIdIncludingDeleted(userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "user_not_found"));
        }

        String requestedType = body != null ? body.specializationType() : null;
        String normalizedType = normalizeType(requestedType);
        if (normalizedType == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_specialization_type",
                    "message", "specialization_type must be major, field, or all"
            ));
        }
        boolean clearJoins = body != null && Boolean.TRUE.equals(body.clearJoins());

        var reset = resetJoinLimits(userId, normalizedType, clearJoins);

        audit.log(authRes.admin().id, "specialization_join_limits.reset", "user", userId,
                "specialization_type=" + normalizedType + " clear_joins=" + clearJoins);

        return ResponseEntity.ok(Map.of(
                "status", "reset",
                "user_id", userId,
                "specialization_type", normalizedType,
                "clear_joins", clearJoins,
                "cooldowns_cleared", reset.cooldownsCleared(),
                "joins_removed", reset.joinsRemoved()
        ));
    }

    private String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) return "all";
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "all" -> "all";
            case "major" -> "major";
            case "field" -> "field";
            default -> null;
        };
    }

    private ResetCounts resetJoinLimits(long userId, String normalizedType, boolean clearJoins) {
        int clearedCooldowns = 0;
        int removedJoins = 0;
        if ("all".equals(normalizedType) || "major".equals(normalizedType)) {
            if (specializationLimits.deleteLastChange(userId, "major", SPECIALIZATION_LIMIT_SCOPE)) clearedCooldowns++;
            if (clearJoins) removedJoins += specializationJoins.deleteJoinedByType(userId, "major");
        }
        if ("all".equals(normalizedType) || "field".equals(normalizedType)) {
            if (specializationLimits.deleteLastChange(userId, "field", SPECIALIZATION_LIMIT_SCOPE)) clearedCooldowns++;
            if (clearJoins) removedJoins += specializationJoins.deleteJoinedByType(userId, "field");
        }
        return new ResetCounts(clearedCooldowns, removedJoins);
    }

    private record ResetCounts(int cooldownsCleared, int joinsRemoved) {}

    public record RevokeRequest(String reason) {}

    public record ResetJoinLimitsRequest(
            @JsonProperty("specialization_type") String specializationType,
            @JsonProperty("clear_joins") Boolean clearJoins
    ) {}
}
