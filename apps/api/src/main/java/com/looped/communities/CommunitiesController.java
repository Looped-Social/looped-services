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

@RestController
@RequestMapping("/v1/communities")
public class CommunitiesController {
    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityFollowsRepository follows;
    private final SpecializationJoinsRepository specializationJoins;
    private final SpecializationMembershipService specializationMemberships;
    private final CommunityVerificationsRepository verifications;
    private final UserCommunityBanRepository communityBans;

    public CommunitiesController(UserRepository users,
                                 CommunitiesRepository communities,
                                 CommunityFollowsRepository follows,
                                 SpecializationJoinsRepository specializationJoins,
                                 SpecializationMembershipService specializationMemberships,
                                 CommunityVerificationsRepository verifications,
                                 UserCommunityBanRepository communityBans) {
        this.users = users;
        this.communities = communities;
        this.follows = follows;
        this.specializationJoins = specializationJoins;
        this.specializationMemberships = specializationMemberships;
        this.verifications = verifications;
        this.communityBans = communityBans;
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
        Map<String, Object> out = new HashMap<>();
        out.put("id", community.id);
        out.put("kind", community.kind);
        out.put("name", community.name);
        if (community.shortName != null) out.put("short_name", community.shortName);
        if (community.description != null) out.put("description", community.description);
        if (community.imageUrl != null) out.put("image_url", community.imageUrl);
        out.put("member_count", verifications.countActiveVerifiedMembers(community.id));
        if (community.specializationType != null) out.put("specialization_type", community.specializationType);
        out.put("is_following", follows.exists(actor.get().id, id));
        if ("specialization".equalsIgnoreCase(community.kind)) {
            String t = community.specializationType == null ? "" : community.specializationType.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.equals("major") || t.equals("field")) {
                out.put("is_joined", specializationJoins.exists(actor.get().id, id));
                var snap = specializationMemberships.joinLimitSnapshotForUserId(actor.get().id, t);
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
                if (snap.requiredVerificationKind() != null) joinLimit.put("required_verification_kind", snap.requiredVerificationKind());
                out.put("join_limit", joinLimit);
            }
        }
        return ResponseEntity.ok(out);
    }
}
