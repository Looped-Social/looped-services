package com.looped.posts;

import com.looped.anon.AnonProofService;
import com.looped.notifications.NotificationPublisher;
import com.looped.principals.PrincipalRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepostsService {
    private final PostRepository posts;
    private final RepostsRepository reposts;
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final AnonProofService anonProofs;
    private final NotificationPublisher notifications;

    public RepostsService(PostRepository posts,
                          RepostsRepository reposts,
                          UserRepository users,
                          PrincipalRepository principals,
                          AnonProofService anonProofs,
                          NotificationPublisher notifications) {
        this.posts = posts;
        this.reposts = reposts;
        this.users = users;
        this.principals = principals;
        this.anonProofs = anonProofs;
        this.notifications = notifications;
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
            actorPrincipalId = verified.actor().principalId();
        } else {
            if (firebaseUid == null) return ToggleResult.userNotProvisioned();
            var user = users.findByFirebaseUid(firebaseUid);
            if (user.isEmpty()) return ToggleResult.userNotProvisioned();
            var principal = principals.createForUser(user.get().id);
            actorPrincipalId = principal.id;
        }
        if (post.get().visibility != null && !post.get().visibility.equalsIgnoreCase("public")
                && actorPrincipalId != post.get().authorPrincipalId) {
            return ToggleResult.notFound();
        }

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
            actorPrincipalId = verified.actor().principalId();
        } else {
            if (firebaseUid == null) return ToggleResult.userNotProvisioned();
            var user = users.findByFirebaseUid(firebaseUid);
            if (user.isEmpty()) return ToggleResult.userNotProvisioned();
            var principal = principals.createForUser(user.get().id);
            actorPrincipalId = principal.id;
        }
        if (post.get().visibility != null && !post.get().visibility.equalsIgnoreCase("public")
                && actorPrincipalId != post.get().authorPrincipalId) {
            return ToggleResult.notFound();
        }

        boolean deleted = reposts.deleteIfPresent(actorPrincipalId, postId);
        int count = deleted ? reposts.decrementPostReposts(postId) : reposts.repostCount(postId);
        return ToggleResult.ok(deleted, false, count);
    }
    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, INVALID_SIGNATURE }

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

        static ToggleResult invalidSignature() {
            return new ToggleResult(Status.INVALID_SIGNATURE, false, false, 0);
        }
    }
}
