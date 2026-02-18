package com.looped.posts;

import com.looped.polls.PollsService;
import com.looped.users.UserCommunityBanRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PostViewerCapabilitiesService {
    private final UserRepository users;
    private final UserCommunityBanRepository communityBans;
    private final CommunityInteractionLockService interactionLocks;

    public PostViewerCapabilitiesService(UserRepository users,
                                         UserCommunityBanRepository communityBans,
                                         CommunityInteractionLockService interactionLocks) {
        this.users = users;
        this.communityBans = communityBans;
        this.interactionLocks = interactionLocks;
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
        UserCommunityBanRepository.ActiveBanScope banScope = communityBans.activeScope(viewerUserId, communityIds);

        Map<Long, CommunityInteractionLockService.LockEvaluation> lockByCommunityId = new HashMap<>();
        for (Long communityId : communityIds) {
            boolean banned = banScope.isBanned(communityId);
            lockByCommunityId.put(
                    communityId,
                    interactionLocks.evaluate(viewerUserId, viewerCompanyId, communityId, banned)
            );
        }

        Map<Long, Map<String, Object>> out = new HashMap<>();
        for (PostRepository.PostRow row : posts) {
            CommunityInteractionLockService.LockEvaluation gate = row.communityId == null
                    ? CommunityInteractionLockService.LockEvaluation.allowed()
                    : lockByCommunityId.getOrDefault(row.communityId, CommunityInteractionLockService.LockEvaluation.allowed());
            PollsService.PollView poll = pollsByPostId == null ? null : pollsByPostId.get(row.id);
            boolean hasPoll = poll != null;
            boolean pollOpen = hasPoll && "OPEN".equalsIgnoreCase(poll.status());

            boolean canComment = gate.canInteract();
            boolean canReply = gate.canInteract();
            boolean canLike = gate.canInteract();
            boolean canVote = hasPoll && pollOpen && gate.canInteract();
            boolean canInteract = canComment && canReply && canLike && (!pollOpen || canVote);

            boolean sameCompany = viewerCompanyId != null && row.companyId == viewerCompanyId.longValue();
            boolean isAuthor = row.authorId != null && row.authorId == viewerUserId;
            boolean canRepost = !banScope.isBanned(row.communityId) && sameCompany && !isAuthor;
            boolean canSave = true;

            Map<String, Object> caps = new HashMap<>();
            caps.put("canInteract", canInteract);
            caps.put("canComment", canComment);
            caps.put("canReply", canReply);
            caps.put("canLike", canLike);
            caps.put("canVote", canVote);
            caps.put("canRepost", canRepost);
            caps.put("canSave", canSave);
            caps.put("lockReason", canInteract ? null : gate.lockReason());
            caps.put("lockContext", canInteract ? null : gate.lockContext());
            caps.put("primaryUnlockAction", gate.primaryUnlockAction());
            caps.put("requiresVerification", gate.requiresVerification());
            caps.put("requiresJoin", gate.requiresJoin());
            out.put(row.id, caps);
        }
        return out;
    }
}
