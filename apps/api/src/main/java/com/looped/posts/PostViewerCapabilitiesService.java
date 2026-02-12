package com.looped.posts;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.SpecializationJoinsRepository;
import com.looped.polls.PollsService;
import com.looped.users.UserCommunityBanRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PostViewerCapabilitiesService {
    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository verifications;
    private final SpecializationJoinsRepository specializationJoins;
    private final UserCommunityBanRepository communityBans;

    public PostViewerCapabilitiesService(UserRepository users,
                                         CommunitiesRepository communities,
                                         CommunityVerificationsRepository verifications,
                                         SpecializationJoinsRepository specializationJoins,
                                         UserCommunityBanRepository communityBans) {
        this.users = users;
        this.communities = communities;
        this.verifications = verifications;
        this.specializationJoins = specializationJoins;
        this.communityBans = communityBans;
    }

    public Map<Long, Map<String, Object>> byPostId(String firebaseUid,
                                                   List<? extends PostRepository.PostRow> posts,
                                                   Map<Long, PollsService.PollView> pollsByPostId) {
        if (firebaseUid == null || firebaseUid.isBlank() || posts == null || posts.isEmpty()) return Map.of();
        var viewer = users.findByFirebaseUid(firebaseUid);
        if (viewer.isEmpty() || viewer.get().companyId == null) return Map.of();
        return computeByPostId(viewer.get().id, viewer.get().companyId, posts, pollsByPostId);
    }

    public Map<Long, Map<String, Object>> byPostId(long viewerUserId,
                                                   Long viewerCompanyId,
                                                   List<? extends PostRepository.PostRow> posts,
                                                   Map<Long, PollsService.PollView> pollsByPostId) {
        if (posts == null || posts.isEmpty()) return Map.of();
        if (viewerCompanyId == null) return Map.of();
        return computeByPostId(viewerUserId, viewerCompanyId, posts, pollsByPostId);
    }

    private Map<Long, Map<String, Object>> computeByPostId(long viewerUserId,
                                                           Long viewerCompanyId,
                                                           List<? extends PostRepository.PostRow> posts,
                                                           Map<Long, PollsService.PollView> pollsByPostId) {
        Set<Long> communityIds = posts.stream()
                .map(p -> p.communityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, CommunitiesRepository.CommunityRow> communitiesById = communities.findByIds(communityIds);
        Map<Long, CommunityVerificationsRepository.VerificationState> verificationStates =
                verifications.statesByCommunityIds(viewerUserId, communityIds);
        Set<Long> joinRequiredIds = communitiesById.values().stream()
                .filter(c -> c != null && "specialization".equalsIgnoreCase(c.kind))
                .filter(c -> requiresSpecializationJoin(c.specializationType))
                .map(c -> c.id)
                .collect(Collectors.toSet());
        Set<Long> joinedIds = specializationJoins.joinedIds(viewerUserId, joinRequiredIds);
        UserCommunityBanRepository.ActiveBanScope banScope = communityBans.activeScope(viewerUserId, communityIds);
        OffsetDateTime now = OffsetDateTime.now();

        Map<Long, Map<String, Object>> out = new HashMap<>();
        for (PostRepository.PostRow row : posts) {
            CommunityGate gate = resolveCommunityGate(row.communityId, communitiesById, verificationStates, joinedIds, banScope, now);
            PollsService.PollView poll = pollsByPostId == null ? null : pollsByPostId.get(row.id);
            boolean hasPoll = poll != null;
            boolean pollOpen = hasPoll && "OPEN".equalsIgnoreCase(poll.status());

            boolean canComment = gate.coreAllowed();
            boolean canReply = gate.coreAllowed();
            boolean canLike = gate.coreAllowed();
            boolean canVote = hasPoll && pollOpen && gate.coreAllowed();
            boolean canInteract = canComment && canReply && canLike && (!pollOpen || canVote);

            boolean sameCompany = viewerCompanyId != null && row.companyId == viewerCompanyId.longValue();
            boolean isAuthor = row.authorId != null && row.authorId == viewerUserId;
            boolean canRepost = !banScope.isBanned(row.communityId) && sameCompany && !isAuthor;
            boolean canSave = true;

            String lockReason = canInteract
                    ? null
                    : (gate.lockReason() == null ? LockReason.UNKNOWN_RESTRICTION.name() : gate.lockReason().name());

            Map<String, Object> caps = new HashMap<>();
            caps.put("canInteract", canInteract);
            caps.put("canComment", canComment);
            caps.put("canReply", canReply);
            caps.put("canLike", canLike);
            caps.put("canVote", canVote);
            caps.put("canRepost", canRepost);
            caps.put("canSave", canSave);
            caps.put("lockReason", lockReason);
            caps.put("requiresVerification", gate.requiresVerification());
            caps.put("requiresJoin", gate.requiresJoin());
            out.put(row.id, caps);
        }
        return out;
    }

    private CommunityGate resolveCommunityGate(Long communityId,
                                               Map<Long, CommunitiesRepository.CommunityRow> communitiesById,
                                               Map<Long, CommunityVerificationsRepository.VerificationState> verificationStates,
                                               Collection<Long> joinedIds,
                                               UserCommunityBanRepository.ActiveBanScope banScope,
                                               OffsetDateTime now) {
        if (communityId == null) {
            return new CommunityGate(true, false, false, null);
        }
        if (banScope.isBanned(communityId)) {
            return new CommunityGate(false, false, false, LockReason.COMMUNITY_BANNED);
        }
        var community = communitiesById.get(communityId);
        if (community == null || community.kind == null) {
            return new CommunityGate(false, false, false, LockReason.UNKNOWN_RESTRICTION);
        }
        if ("specialization".equalsIgnoreCase(community.kind)) {
            boolean requiresJoin = requiresSpecializationJoin(community.specializationType);
            if (!requiresJoin) {
                return new CommunityGate(true, false, false, null);
            }
            boolean joined = joinedIds.contains(communityId);
            return joined
                    ? new CommunityGate(true, false, true, null)
                    : new CommunityGate(false, false, true, LockReason.SPECIALIZATION_NOT_JOINED);
        }
        var state = verificationStates.get(communityId);
        boolean active = state != null
                && state.verified()
                && (state.expiresAt() == null || state.expiresAt().isAfter(now));
        if (active) {
            return new CommunityGate(true, true, false, null);
        }
        boolean expired = state != null
                && state.verified()
                && state.expiresAt() != null
                && !state.expiresAt().isAfter(now);
        return new CommunityGate(
                false,
                true,
                false,
                expired ? LockReason.VERIFICATION_EXPIRED : LockReason.COMMUNITY_NOT_VERIFIED
        );
    }

    private boolean requiresSpecializationJoin(String specializationType) {
        if (specializationType == null) return false;
        String t = specializationType.trim().toLowerCase(Locale.ROOT);
        return t.equals("major") || t.equals("field");
    }

    private record CommunityGate(boolean coreAllowed,
                                 boolean requiresVerification,
                                 boolean requiresJoin,
                                 LockReason lockReason) {}

    enum LockReason {
        COMMUNITY_NOT_VERIFIED,
        SPECIALIZATION_NOT_JOINED,
        USER_NOT_VERIFIED,
        VERIFICATION_EXPIRED,
        COMMUNITY_BANNED,
        UNKNOWN_RESTRICTION
    }
}
