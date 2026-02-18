package com.looped.posts;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.SpecializationJoinsRepository;
import com.looped.communities.SpecializationMembershipService;
import com.looped.companies.CompanyRepository;
import com.looped.users.UserCommunityBanRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class CommunityInteractionLockService {
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository verifications;
    private final SpecializationJoinsRepository specializationJoins;
    private final SpecializationMembershipService specializationMemberships;
    private final UserCommunityBanRepository communityBans;
    private final CompanyRepository companies;

    public CommunityInteractionLockService(CommunitiesRepository communities,
                                           CommunityVerificationsRepository verifications,
                                           SpecializationJoinsRepository specializationJoins,
                                           SpecializationMembershipService specializationMemberships,
                                           UserCommunityBanRepository communityBans,
                                           CompanyRepository companies) {
        this.communities = communities;
        this.verifications = verifications;
        this.specializationJoins = specializationJoins;
        this.specializationMemberships = specializationMemberships;
        this.communityBans = communityBans;
        this.companies = companies;
    }

    public LockEvaluation evaluate(long userId, Long userCompanyId, Long communityId) {
        return evaluate(userId, userCompanyId, communityId, null);
    }

    public LockEvaluation evaluate(long userId, Long userCompanyId, Long communityId, Boolean bannedOverride) {
        if (communityId == null) {
            return LockEvaluation.allowed();
        }

        boolean banned = bannedOverride != null ? bannedOverride : communityBans.isBanned(userId, communityId);
        var community = communities.findById(communityId).orElse(null);
        if (community == null) {
            return LockEvaluation.locked(
                    "UNKNOWN_RESTRICTION",
                    "unknown_restriction",
                    false,
                    false,
                    null,
                    primaryUnlockAction("NONE", null, null, null)
            );
        }

        String communityKind = normalizeCommunityKind(community.kind);
        if (banned) {
            return LockEvaluation.locked(
                    "COMMUNITY_BANNED",
                    "community_banned",
                    false,
                    false,
                    lockContext(
                            community,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null
                    ),
                    primaryUnlockAction("NONE", null, null, null)
            );
        }

        if ("specialization".equals(communityKind)) {
            String specializationType = normalizeSpecializationType(community.specializationType);
            boolean requiresJoin = "major".equals(specializationType) || "field".equals(specializationType);
            if (!requiresJoin) {
                return LockEvaluation.allowed();
            }

            if (specializationJoins.exists(userId, community.id)) {
                return LockEvaluation.allowedWithJoinRequirement();
            }

            var snapshot = specializationMemberships.joinLimitSnapshotForUserId(userId, specializationType);
            String requiredKind = normalizeRequiredVerificationKind(snapshot.requiredVerificationKind());
            Boolean alreadyVerifiedElsewhere = requiredKind == null
                    ? null
                    : verifications.hasActiveVerifiedCommunityOfKind(userId, requiredKind);
            VerifyTarget target = null;
            String lockReason = "SPECIALIZATION_NOT_JOINED";
            String errorCode = "specialization_not_joined";
            String actionType = "JOIN_SPECIALIZATION";
            Long actionCommunityId = community.id;
            Long actionSpecializationId = community.id;

            if (snapshot.blockedReason() != null && snapshot.blockedReason().startsWith("verify_")) {
                lockReason = "SPECIALIZATION_VERIFICATION_REQUIRED";
                errorCode = "specialization_verification_required";
                actionType = "VERIFY_PARENT_THEN_JOIN";
                target = resolveVerifyTarget(userCompanyId, requiredKind);
                actionCommunityId = target == null ? null : target.communityId();
            }

            return LockEvaluation.locked(
                    lockReason,
                    errorCode,
                    false,
                    true,
                    lockContext(
                            community,
                            community.id,
                            community.name,
                            specializationType,
                            snapshot.remaining(),
                            snapshot.limit(),
                            snapshot.cooldownActive(),
                            snapshot.cooldownEndsAt(),
                            requiredKind,
                            target,
                            alreadyVerifiedElsewhere
                    ),
                    primaryUnlockAction(actionType, actionCommunityId, actionSpecializationId, null)
            );
        }

        var state = verifications.statesByCommunityIds(userId, java.util.Set.of(community.id)).get(community.id);
        OffsetDateTime now = OffsetDateTime.now();
        boolean active = state != null
                && state.verified()
                && (state.expiresAt() == null || state.expiresAt().isAfter(now));
        if (active) {
            return LockEvaluation.allowedWithVerificationRequirement();
        }

        boolean expired = state != null
                && state.verified()
                && state.expiresAt() != null
                && !state.expiresAt().isAfter(now);
        String requiredKind = normalizeRequiredVerificationKind(communityKind);
        Boolean alreadyVerifiedElsewhere = requiredKind == null
                ? null
                : verifications.hasActiveVerifiedCommunityOfKind(userId, requiredKind);

        return LockEvaluation.locked(
                expired ? "VERIFICATION_EXPIRED" : "COMMUNITY_NOT_VERIFIED",
                expired ? "verification_expired" : "community_not_verified",
                true,
                false,
                lockContext(
                        community,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        requiredKind,
                        new VerifyTarget(community.id, community.name),
                        alreadyVerifiedElsewhere
                ),
                primaryUnlockAction("VERIFY_COMMUNITY", community.id, null, null)
        );
    }

    private Map<String, Object> lockContext(CommunitiesRepository.CommunityRow community,
                                            Long specializationId,
                                            String specializationName,
                                            String specializationType,
                                            Integer joinCreditsRemaining,
                                            Integer joinCreditsLimit,
                                            Boolean joinCooldownActive,
                                            OffsetDateTime joinCooldownEndsAt,
                                            String requiredVerificationKind,
                                            VerifyTarget verifyTarget,
                                            Boolean alreadyVerifiedElsewhere) {
        return lockContext(
                community,
                specializationId,
                specializationName,
                specializationType,
                joinCreditsRemaining,
                joinCreditsLimit,
                joinCooldownActive,
                joinCooldownEndsAt,
                requiredVerificationKind,
                verifyTarget,
                alreadyVerifiedElsewhere,
                false
        );
    }

    private Map<String, Object> lockContext(CommunitiesRepository.CommunityRow community,
                                            Long specializationId,
                                            String specializationName,
                                            String specializationType,
                                            Integer joinCreditsRemaining,
                                            Integer joinCreditsLimit,
                                            Boolean joinCooldownActive,
                                            OffsetDateTime joinCooldownEndsAt,
                                            String requiredVerificationKind) {
        return lockContext(
                community,
                specializationId,
                specializationName,
                specializationType,
                joinCreditsRemaining,
                joinCreditsLimit,
                joinCooldownActive,
                joinCooldownEndsAt,
                requiredVerificationKind,
                null,
                null,
                false
        );
    }

    private Map<String, Object> lockContext(CommunitiesRepository.CommunityRow community,
                                            Long specializationId,
                                            String specializationName,
                                            String specializationType,
                                            Integer joinCreditsRemaining,
                                            Integer joinCreditsLimit,
                                            Boolean joinCooldownActive,
                                            OffsetDateTime joinCooldownEndsAt,
                                            String requiredVerificationKind,
                                            VerifyTarget verifyTarget,
                                            Boolean alreadyVerifiedElsewhere,
                                            boolean includeUnknownDefaults) {
        Map<String, Object> out = new HashMap<>();
        out.put("communityId", community == null ? null : community.id);
        out.put("communityName", community == null ? null : community.name);
        out.put("communityKind", community == null ? "unknown" : normalizeCommunityKind(community.kind));
        out.put("specializationId", specializationId);
        out.put("specializationName", specializationName);
        out.put("specializationType", specializationType == null ? (includeUnknownDefaults ? "unknown" : null) : specializationType);
        out.put("joinCreditsRemaining", joinCreditsRemaining);
        out.put("joinCreditsLimit", joinCreditsLimit);
        out.put("joinCooldownActive", joinCooldownActive);
        out.put("joinCooldownEndsAt", joinCooldownEndsAt);
        out.put("requiredVerificationKind", requiredVerificationKind);
        out.put("verifyTargetCommunityId", verifyTarget == null ? null : verifyTarget.communityId());
        out.put("verifyTargetCommunityName", verifyTarget == null ? null : verifyTarget.communityName());
        out.put("alreadyVerifiedElsewhere", alreadyVerifiedElsewhere);
        return out;
    }

    private Map<String, Object> primaryUnlockAction(String type, Long communityId, Long specializationId, String label) {
        Map<String, Object> out = new HashMap<>();
        out.put("type", type == null ? "NONE" : type);
        out.put("communityId", communityId);
        out.put("specializationId", specializationId);
        out.put("label", label);
        return out;
    }

    private VerifyTarget resolveVerifyTarget(Long userCompanyId, String requiredKind) {
        if (requiredKind == null) return null;
        if ("company".equals(requiredKind) && userCompanyId != null) {
            var company = companies.findById(userCompanyId);
            if (company.isPresent()) {
                var byName = communities.findByKindAndName("company", company.get().name);
                if (byName.isPresent()) {
                    return new VerifyTarget(byName.get().id, byName.get().name);
                }
            }
        }
        var top = communities.findTopByKind(requiredKind);
        return top.map(row -> new VerifyTarget(row.id, row.name)).orElse(null);
    }

    private String normalizeCommunityKind(String kind) {
        if (kind == null || kind.isBlank()) return "unknown";
        String normalized = kind.trim().toLowerCase(Locale.ROOT);
        if ("company".equals(normalized) || "school".equals(normalized) || "specialization".equals(normalized)) {
            return normalized;
        }
        return "unknown";
    }

    private String normalizeSpecializationType(String type) {
        if (type == null || type.isBlank()) return "unknown";
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        if ("major".equals(normalized) || "field".equals(normalized)) return normalized;
        return "unknown";
    }

    private String normalizeRequiredVerificationKind(String kind) {
        if (kind == null || kind.isBlank()) return null;
        String normalized = kind.trim().toLowerCase(Locale.ROOT);
        if ("company".equals(normalized) || "school".equals(normalized)) return normalized;
        return null;
    }

    private record VerifyTarget(Long communityId, String communityName) {}

    public record LockEvaluation(boolean canInteract,
                                 boolean requiresVerification,
                                 boolean requiresJoin,
                                 String lockReason,
                                 String errorCode,
                                 Map<String, Object> lockContext,
                                 Map<String, Object> primaryUnlockAction) {
        static LockEvaluation allowed() {
            return new LockEvaluation(true, false, false, null, null, null, mapWithNoneAction());
        }

        static LockEvaluation allowedWithVerificationRequirement() {
            return new LockEvaluation(true, true, false, null, null, null, mapWithNoneAction());
        }

        static LockEvaluation allowedWithJoinRequirement() {
            return new LockEvaluation(true, false, true, null, null, null, mapWithNoneAction());
        }

        static LockEvaluation locked(String lockReason,
                                     String errorCode,
                                     boolean requiresVerification,
                                     boolean requiresJoin,
                                     Map<String, Object> lockContext,
                                     Map<String, Object> primaryUnlockAction) {
            return new LockEvaluation(
                    false,
                    requiresVerification,
                    requiresJoin,
                    lockReason,
                    errorCode,
                    lockContext,
                    primaryUnlockAction
            );
        }

        private static Map<String, Object> mapWithNoneAction() {
            Map<String, Object> out = new HashMap<>();
            out.put("type", "NONE");
            out.put("communityId", null);
            out.put("specializationId", null);
            out.put("label", null);
            return out;
        }
    }
}
