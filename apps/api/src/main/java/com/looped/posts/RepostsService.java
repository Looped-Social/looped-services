package com.looped.posts;

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
    private final NotificationPublisher notifications;

    public RepostsService(PostRepository posts,
                          RepostsRepository reposts,
                          UserRepository users,
                          PrincipalRepository principals,
                          NotificationPublisher notifications) {
        this.posts = posts;
        this.reposts = reposts;
        this.users = users;
        this.principals = principals;
        this.notifications = notifications;
    }

    @Transactional
    public ToggleResult repost(String firebaseUid, long postId) {
        if (firebaseUid == null) return ToggleResult.userNotProvisioned();
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return ToggleResult.userNotProvisioned();

        var post = posts.findById(postId);
        if (post.isEmpty()) return ToggleResult.notFound();
        if (user.get().companyId.longValue() != post.get().companyId) return ToggleResult.forbidden();

        var principal = principals.createForUser(user.get().id);
        if (principal.id == post.get().authorPrincipalId) return ToggleResult.selfRepostNotAllowed();

        boolean created = reposts.insertIfAbsent(principal.id, postId);
        int count = created ? reposts.incrementPostReposts(postId) : reposts.repostCount(postId);
        if (created) {
            try {
                notifications.notifyRepost(post.get(), principal.id);
            } catch (RuntimeException ignored) {}
        }
        return ToggleResult.ok(created, true, count);
    }

    @Transactional
    public ToggleResult unrepost(String firebaseUid, long postId) {
        if (firebaseUid == null) return ToggleResult.userNotProvisioned();
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return ToggleResult.userNotProvisioned();

        var post = posts.findById(postId);
        if (post.isEmpty()) return ToggleResult.notFound();
        if (user.get().companyId.longValue() != post.get().companyId) return ToggleResult.forbidden();

        var principal = principals.createForUser(user.get().id);

        boolean deleted = reposts.deleteIfPresent(principal.id, postId);
        int count = deleted ? reposts.decrementPostReposts(postId) : reposts.repostCount(postId);
        return ToggleResult.ok(deleted, false, count);
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN, SELF_REPOST_NOT_ALLOWED }

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
    }
}

