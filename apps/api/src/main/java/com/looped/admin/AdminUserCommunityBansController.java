package com.looped.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.looped.communities.CommunitiesRepository;
import com.looped.users.UserCommunityBanRepository;
import com.looped.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/users")
public class AdminUserCommunityBansController {
    private final AdminAuthService auth;
    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final UserCommunityBanRepository bans;
    private final AdminAuditRepository audit;

    public AdminUserCommunityBansController(AdminAuthService auth,
                                            UserRepository users,
                                            CommunitiesRepository communities,
                                            UserCommunityBanRepository bans,
                                            AdminAuditRepository audit) {
        this.auth = auth;
        this.users = users;
        this.communities = communities;
        this.bans = bans;
        this.audit = audit;
    }

    @GetMapping("/{id}/community-bans")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable("id") long userId,
                                  @RequestParam(value = "active", required = false, defaultValue = "true") boolean activeOnly) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.BAN_USER);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (users.findByIdIncludingDeleted(userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "user_not_found"));
        }
        List<Map<String, Object>> items = bans.listForUser(userId, activeOnly).stream().map(b -> {
            Map<String, Object> out = new HashMap<>();
            out.put("id", b.id);
            out.put("scope", b.scope);
            out.put("community_id", b.communityId);
            out.put("community_name", b.communityName);
            out.put("reason", b.reason);
            out.put("created_at", b.createdAt);
            out.put("expires_at", b.expiresAt);
            out.put("created_by", b.createdBy);
            out.put("revoked_at", b.revokedAt);
            out.put("revoked_by", b.revokedBy);
            return out;
        }).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    @PostMapping("/{id}/community-bans")
    public ResponseEntity<?> ban(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable("id") long userId,
                                 @Validated @RequestBody CreateCommunityBanRequest body) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.BAN_USER);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (users.findByIdIncludingDeleted(userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "user_not_found"));
        }

        OffsetDateTime expiresAt = parseExpiry(body.expiresAt(), body.durationSeconds());
        if (expiresAt == OffsetDateTime.MIN) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "invalid_expires_at"));
        }

        boolean all = body.allCommunities() != null && body.allCommunities();
        List<Long> communityIds = body.communityIds() == null ? List.of() : body.communityIds().stream().distinct().toList();
        if (!all && communityIds.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "community_ids_required",
                    "message", "Provide communityIds or set allCommunities=true"
            ));
        }
        if (all && !communityIds.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_request",
                    "message", "Do not provide communityIds when allCommunities=true"
            ));
        }

        java.util.List<Long> createdIds = new java.util.ArrayList<>();
        if (all) {
            createdIds.add(bans.banAllCommunities(userId, authRes.admin().id, body.reason(), expiresAt));
            audit.log(authRes.admin().id, "user.community_ban.create", "user", userId, "scope=all_communities");
        } else {
            for (Long communityId : communityIds) {
                if (communityId == null) continue;
                if (communities.findById(communityId).isEmpty()) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                            "error", "community_not_found",
                            "community_id", communityId
                    ));
                }
                createdIds.add(bans.banCommunity(userId, communityId, authRes.admin().id, body.reason(), expiresAt));
            }
            audit.log(authRes.admin().id, "user.community_ban.create", "user", userId, "scope=community count=" + createdIds.size());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "status", "banned",
                "user_id", userId,
                "ban_ids", createdIds
        ));
    }

    @PostMapping("/{id}/community-bans/{banId}/revoke")
    public ResponseEntity<?> revoke(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable("id") long userId,
                                    @PathVariable("banId") long banId) {
        String email = jwt.getClaimAsString("email");
        var authRes = auth.requirePermission(jwt.getSubject(), email, AdminPermissions.BAN_USER);
        if (authRes.status() != AdminAuthService.Status.OK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (users.findByIdIncludingDeleted(userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "user_not_found"));
        }
        boolean revoked = bans.revokeForUser(banId, userId, authRes.admin().id);
        if (!revoked) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "ban_not_found"));
        }
        audit.log(authRes.admin().id, "user.community_ban.revoke", "user", userId, "ban_id=" + banId);
        return ResponseEntity.ok(Map.of("status", "revoked", "user_id", userId, "ban_id", banId));
    }

    private OffsetDateTime parseExpiry(String expiresAtRaw, Long durationSeconds) {
        if (expiresAtRaw != null && !expiresAtRaw.isBlank()) {
            try {
                return OffsetDateTime.parse(expiresAtRaw);
            } catch (DateTimeParseException e) {
                return OffsetDateTime.MIN;
            }
        }
        if (durationSeconds != null && durationSeconds > 0) {
            return OffsetDateTime.now().plusSeconds(durationSeconds);
        }
        return null;
    }

    public record CreateCommunityBanRequest(
            @JsonProperty("allCommunities") Boolean allCommunities,
            @JsonProperty("communityIds") List<Long> communityIds,
            @JsonProperty("duration_seconds") Long durationSeconds,
            @JsonProperty("expires_at") String expiresAt,
            String reason
    ) {}
}
