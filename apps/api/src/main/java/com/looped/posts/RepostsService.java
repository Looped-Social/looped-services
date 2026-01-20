package com.looped.posts;

import com.looped.anon.AnonProofService;
import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.notifications.NotificationPublisher;
import com.looped.principals.PrincipalRepository;
import com.looped.users.UserCommunityBanRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepostsService {
    private final PostRepository posts;
    private final RepostsRepository reposts;
    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final PrincipalRepository principals;
    private final AnonProofService anonProofs;
    private final NotificationPublisher notifications;
    private final UserCommunityBanRepository communityBans;

    public RepostsService(PostRepository posts,
                          RepostsRepository reposts,
                          UserRepository users,
                          CommunitiesRepository communities,
                          CommunityVerificationsRepository communityVerifications,
                          PrincipalRepository principals,
                          AnonProofService anonProofs,
                          NotificationPublisher notifications,
                          UserCommunityBanRepository communityBans) {
        this.posts = posts;
        this.reposts = reposts;
        this.users = users;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.principals = principals;
        this.anonProofs = anonProofs;
        this.notifications = notifications;
        this.communityBans = communityBans;
    }

    @Transactional
    public ToggleResult repost(String firebaseUid, long postId) {
        var post = posts.findById(postId);
        if (post.isEmpty()) return ToggleResult.notFound();
        return repost(firebaseUid, postId, null);
    }

    @Transactional
    public ToggleResult repost(String firebaseUid, long postId, AnonProofService.AnonActionProof anonProof) {
        var post = posts.findById(postId);
        if (post.isEmpty()) return ToggleResult.notFound();

        long actorPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            var verified = anonProofs.verifyActionScoped(anonProof, "repost", postId, post.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return ToggleResult.invalidSignature();
            if (verified.actor().companyId() != null && verified.actor().companyId() != post.get().companyId) {
                return ToggleResult.forbidden();
            }
            actorPrincipalId = verified.actor().principalId();
        } else {
            if (firebaseUid == null) return ToggleResult.userNotProvisioned();
            var user = users.findByFirebaseUid(firebaseUid);
            if (user.isEmpty() || user.get().companyId == null) return ToggleResult.userNotProvisioned();
            if (user.get().companyId.longValue() != post.get().companyId) return ToggleResult.forbidden();
            if (post.get().communityId != null && communityBans.isBanned(user.get().id, post.get().communityId)) {
                return ToggleResult.communityBanned();
            }
            if (requiresVerification(post.get().communityId)
                    && !communityVerifications.isVerified(user.get().id, post.get().communityId)) {
                return ToggleResult.notVerified();
            }
            var principal = principals.createForUser(user.get().id);
            actorPrincipalId = principal.id;
        }

        if (actorPrincipalId == post.get().authorPrincipalId) return ToggleResult.selfRepostNotAllowed();

        boolean created = reposts.insertIfAbsent(actorPrincipalId, postId);
        int count = created ? reposts.incrementPostReposts(postId) : reposts.repostCount(postId);
        if (created) {
            try {
                notifications.notifyRepost(post.get(), actorPrincipalId);
            } catch (RuntimeException ignored) {}
        }
        return ToggleResult.ok(created, true, count);
    }

    @Transactional
    public ToggleResult unrepost(String firebaseUid, long postId) {
        return unrepost(firebaseUid, postId, null);
    }

    @Transactional
    public ToggleResult unrepost(String firebaseUid, long postId, AnonProofService.AnonActionProof anonProof) {
        var post = posts.findById(postId);
        if (post.isEmpty()) return ToggleResult.notFound();

        long actorPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            var verified = anonProofs.verifyActionScoped(anonProof, "unrepost", postId, post.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return ToggleResult.invalidSignature();
            if (verified.actor().companyId() != null && verified.actor().companyId() != post.get().companyId) {
                return ToggleResult.forbidden();
            }
            actorPrincipalId = verified.actor().principalId();
        } else {
            if (firebaseUid == null) return ToggleResult.userNotProvisioned();
            var user = users.findByFirebaseUid(firebaseUid);
            if (user.isEmpty() || user.get().companyId == null) return ToggleResult.userNotProvisioned();
            if (user.get().companyId.longValue() != post.get().companyId) return ToggleResult.forbidden();
            if (post.get().communityId != null && communityBans.isBanned(user.get().id, post.get().communityId)) {
                return ToggleResult.communityBanned();
            }
            if (requiresVerification(post.get().communityId)
                    && !communityVerifications.isVerified(user.get().id, post.get().communityId)) {
                return ToggleResult.notVerified();
            }
            var principal = principals.createForUser(user.get().id);
            actorPrincipalId = principal.id;
        }

        boolean deleted = reposts.deleteIfPresent(actorPrincipalId, postId);
        int count = deleted ? reposts.decrementPostReposts(postId) : reposts.repostCount(postId);
        return ToggleResult.ok(deleted, false, count);
    }

    private boolean requiresVerification(Long communityId) {
        if (communityId == null) return false;
        var community = communities.findById(communityId);
        return community.isPresent() && !"specialization".equalsIgnoreCase(community.get().kind);
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN, SELF_REPOST_NOT_ALLOWED, COMMUNITY_BANNED, INVALID_SIGNATURE, NOT_VERIFIED }

    public record ToggleResult(Status status, boolean changed, boolean viewerHasReposted, int repostCount) {
        static ToggleResult ok(boolean changed, boolean viewerHasReposted, int repostCount) {
            return new ToggleResult(Status.OK, changed, viewerHasReposted, repostCount);
        }

        static ToggleResult userNotProvisioned() {
            return new ToggleResult(Status.USER_NOT_PROVISIONED, false, false, 0);
        }

        static ToggleResult notFound() {
            return new ToggleResult(Status.NOT_FOUND, false, false, 0);
        }

        static ToggleResult forbidden() {
            return new ToggleResult(Status.FORBIDDEN, false, false, 0);
        }

        static ToggleResult selfRepostNotAllowed() {
            return new ToggleResult(Status.SELF_REPOST_NOT_ALLOWED, false, false, 0);
        }

        static ToggleResult communityBanned() {
            return new ToggleResult(Status.COMMUNITY_BANNED, false, false, 0);
        }

        static ToggleResult invalidSignature() {
            return new ToggleResult(Status.INVALID_SIGNATURE, false, false, 0);
        }

        static ToggleResult notVerified() {
            return new ToggleResult(Status.NOT_VERIFIED, false, false, 0);
        }
    }
}
