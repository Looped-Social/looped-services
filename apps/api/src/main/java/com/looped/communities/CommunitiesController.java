package com.looped.communities;

import com.looped.users.UserRepository;
import com.looped.users.UserCommunityBanRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

import static com.looped.communities.CommunityVisibilityRules.isFieldSpecialization;
import static com.looped.communities.CommunityVisibilityRules.isUserVisible;

@RestController
@RequestMapping("/v1/communities")
public class CommunitiesController {
    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityFollowsRepository follows;
    private final SpecializationJoinsRepository specializationJoins;
    private final SpecializationMembershipService specializationMemberships;
    private final CommunityMemberCountService memberCounts;
    private final UserCommunityBanRepository communityBans;
    private final CommunityViewerStateService viewerState;

    public CommunitiesController(UserRepository users,
                                 CommunitiesRepository communities,
                                 CommunityFollowsRepository follows,
                                 SpecializationJoinsRepository specializationJoins,
                                 SpecializationMembershipService specializationMemberships,
                                 CommunityMemberCountService memberCounts,
                                 UserCommunityBanRepository communityBans,
                                 CommunityViewerStateService viewerState) {
        this.users = users;
        this.communities = communities;
        this.follows = follows;
        this.specializationJoins = specializationJoins;
        this.specializationMemberships = specializationMemberships;
        this.memberCounts = memberCounts;
        this.communityBans = communityBans;
        this.viewerState = viewerState;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getCommunity(@AuthenticationPrincipal Jwt jwt,
                                          @PathVariable("id") long id) {
        var actor = users.findByFirebaseUid(jwt.getSubject());
        if (actor.isEmpty() || actor.get().companyId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned"
            ));
        }
        var communityOpt = communities.findById(id);
        if (communityOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found"
            ));
        }
        if (communityBans.isBanned(actor.get().id, id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_banned"
            ));
        }
        var community = communityOpt.get();
        if (!isUserVisible(community.kind, community.specializationType)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found"
            ));
        }
        Map<String, Object> out = new HashMap<>();
        Boolean specializationJoined = null;
        SpecializationMembershipService.JoinLimitSnapshot joinLimitSnapshot = null;
        out.put("id", community.id);
        out.put("kind", community.kind);
        out.put("name", community.name);
        if (community.shortName != null) out.put("short_name", community.shortName);
        if (community.description != null) out.put("description", community.description);
        CommunityImageSlots.putPayload(out, community.imageUrl, community.profileImageUrl, null);
        out.put("member_count", memberCounts.memberCount(community.id, community.kind));
        if (community.specializationType != null) out.put("specialization_type", community.specializationType);
        if ("specialization".equalsIgnoreCase(community.kind)) {
            Map<String, Object> icon = SpecializationIcons.payloadOrNull(community.iconKind, community.iconValue);
            if (icon != null) out.put("icon", icon);
            SpecializationBrandingPayloads.putPayload(
                    out,
                    community.specializationIconImageUrl,
                    community.specializationBannerImageUrl
            );
        }
        out.put("is_following", follows.exists(actor.get().id, id));
        if (isFieldSpecialization(community.kind, community.specializationType)) {
                specializationJoined = specializationJoins.exists(actor.get().id, id);
                out.put("is_joined", specializationJoined);
                joinLimitSnapshot = specializationMemberships.joinLimitSnapshotForUserId(actor.get().id, "field");
                var snap = joinLimitSnapshot;
                Map<String, Object> joinLimit = new HashMap<>();
                joinLimit.put("specialization_type", snap.specializationType());
                joinLimit.put("limit", snap.limit());
                joinLimit.put("joined_count", snap.joinedCount());
                joinLimit.put("remaining", snap.remaining());
                joinLimit.put("cooldown_months", snap.cooldownMonths());
                joinLimit.put("cooldown_active", snap.cooldownActive());
                if (snap.cooldownEndsAt() != null) joinLimit.put("cooldown_ends_at", snap.cooldownEndsAt());
                if (snap.cooldownDaysRemaining() != null) joinLimit.put("cooldown_days_remaining", snap.cooldownDaysRemaining());
                joinLimit.put("can_join", snap.canJoin());
                if (snap.blockedReason() != null) joinLimit.put("blocked_reason", snap.blockedReason());
                if (snap.requiredVerificationKind() != null) {
                    joinLimit.put("required_verification_kind", snap.requiredVerificationKind());
                    joinLimit.put("join_requires_verification_kind", snap.requiredVerificationKind());
                }
                String joinBlocked = joinBlockedReason(snap.blockedReason());
                if (joinBlocked != null) joinLimit.put("join_blocked_reason", joinBlocked);
                out.put("join_limit", joinLimit);
        }
        out.put("viewer", viewerState.payload(actor.get().id, community, specializationJoined, joinLimitSnapshot));
        return ResponseEntity.ok(out);
    }

    private String joinBlockedReason(String blockedReason) {
        if (blockedReason == null || blockedReason.isBlank()) return null;
        if (blockedReason.startsWith("verify_")) return "verification_required";
        if ("limit".equals(blockedReason)) return "limit";
        if ("cooldown".equals(blockedReason)) return "cooldown";
        return null;
    }
}
