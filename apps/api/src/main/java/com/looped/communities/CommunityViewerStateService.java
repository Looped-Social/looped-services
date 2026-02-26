package com.looped.communities;

import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class CommunityViewerStateService {
    public enum VerificationStatus { active, pending, rejected, expired, none, unknown }
    public enum CannotPostReason { not_verified, not_joined, suspended, read_only, rate_limited, unknown }

    private final CommunityVerificationsRepository verifications;
    private final SpecializationJoinsRepository specializationJoins;

    public CommunityViewerStateService(CommunityVerificationsRepository verifications,
                                       SpecializationJoinsRepository specializationJoins) {
        this.verifications = verifications;
        this.specializationJoins = specializationJoins;
    }

    public Map<String, Object> payload(long userId,
                                       CommunitiesRepository.CommunityRow community,
                                       Boolean specializationJoined,
                                       SpecializationMembershipService.JoinLimitSnapshot joinLimitSnapshot) {
        ViewerState state = compute(userId, community, specializationJoined, joinLimitSnapshot);
        Map<String, Object> out = new HashMap<>();
        out.put("verification_status", state.verificationStatus().name());
        out.put("can_post", state.canPost());
        if (state.cannotPostReason() != null) {
            out.put("cannot_post_reason", state.cannotPostReason().name());
        }
        // Only meaningful for per-community verification (non-specializations).
        if (community != null
                && community.kind != null
                && !"specialization".equalsIgnoreCase(community.kind)) {
            var row = verifications.viewerVerificationRowForUserAndCommunity(userId, community.id);
            out.put("verification_expires_at", row == null ? null : row.expiresAt());
            out.put("verification_verified_at", row == null ? null : row.verifiedAt());
        }
        return out;
    }

    public ViewerState compute(long userId,
                               CommunitiesRepository.CommunityRow community,
                               Boolean specializationJoined,
                               SpecializationMembershipService.JoinLimitSnapshot joinLimitSnapshot) {
        if (community == null || community.kind == null || community.kind.isBlank()) {
            return new ViewerState(VerificationStatus.unknown, false, CannotPostReason.unknown);
        }

        String kind = community.kind.trim().toLowerCase(Locale.ROOT);
        if ("specialization".equals(kind)) {
            String t = community.specializationType == null ? "" : community.specializationType.trim().toLowerCase(Locale.ROOT);
            boolean joinRequired = t.equals("major") || t.equals("field");
            if (!joinRequired) {
                return new ViewerState(VerificationStatus.none, true, null);
            }

            boolean joined = specializationJoined != null ? specializationJoined : specializationJoins.exists(userId, community.id);
            if (joined) {
                return new ViewerState(VerificationStatus.none, true, null);
            }

            CannotPostReason reason = CannotPostReason.not_joined;
            if (joinLimitSnapshot != null) {
                String blocked = joinLimitSnapshot.blockedReason();
                if (blocked != null && blocked.startsWith("verify_")) {
                    reason = CannotPostReason.not_verified;
                } else if (!joinLimitSnapshot.canJoin()) {
                    // Avoid mis-nudging to "Join" when the user is blocked by limit/cooldown.
                    reason = CannotPostReason.unknown;
                }
            }
            return new ViewerState(VerificationStatus.none, false, reason);
        }

        var row = verifications.viewerVerificationRowForUserAndCommunity(userId, community.id);
        VerificationStatus verificationStatus = verificationStatus(row);
        OffsetDateTime now = OffsetDateTime.now();
        boolean canPost = row != null
                && Boolean.TRUE.equals(row.verified())
                && (row.expiresAt() == null || row.expiresAt().isAfter(now));
        CannotPostReason cannotPostReason = canPost ? null : CannotPostReason.not_verified;
        return new ViewerState(verificationStatus, canPost, cannotPostReason);
    }

    private VerificationStatus verificationStatus(CommunityVerificationsRepository.ViewerVerificationRow row) {
        OffsetDateTime now = OffsetDateTime.now();
        boolean active = row != null
                && Boolean.TRUE.equals(row.verified())
                && (row.expiresAt() == null || row.expiresAt().isAfter(now));
        if (active) return VerificationStatus.active;

        boolean expired = row != null
                && Boolean.TRUE.equals(row.verified())
                && row.expiresAt() != null
                && !row.expiresAt().isAfter(now);
        if (expired) return VerificationStatus.expired;

        String latest = row == null ? null : row.latestRequestStatus();
        String normalized = latest == null ? null : latest.trim().toLowerCase(Locale.ROOT);
        if ("pending".equals(normalized)) return VerificationStatus.pending;
        if ("rejected".equals(normalized)) return VerificationStatus.rejected;
        if (normalized == null || normalized.isBlank()) return VerificationStatus.none;
        return VerificationStatus.unknown;
    }

    public record ViewerState(VerificationStatus verificationStatus,
                              boolean canPost,
                              CannotPostReason cannotPostReason) {}
}
