package com.looped.posts;

import com.looped.principals.PrincipalRepository;
import com.looped.notifications.NotificationPublisher;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostSharesService {
    private final PostRepository posts;
    private final PostSharesRepository shares;
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final NotificationPublisher notifications;

    public PostSharesService(PostRepository posts,
                             PostSharesRepository shares,
                             UserRepository users,
                             PrincipalRepository principals,
                             NotificationPublisher notifications) {
        this.posts = posts;
        this.shares = shares;
        this.users = users;
        this.principals = principals;
        this.notifications = notifications;
    }

    @Transactional
    public Result share(String firebaseUid, long postId) {
        var postOpt = posts.findById(postId);
        if (postOpt.isEmpty()) return Result.notFound();
        if (firebaseUid == null) return Result.userNotProvisioned();
        var userOpt = users.findByFirebaseUid(firebaseUid);
        if (userOpt.isEmpty()) return Result.userNotProvisioned();
        var principal = principals.createForUser(userOpt.get().id);
        shares.insert(principal.id, postId);
        shares.incrementPostShares(postId);
        try {
            notifications.notifyRepost(postOpt.get(), principal.id);
        } catch (RuntimeException ignored) {}
        var current = posts.findById(postId).orElseThrow();
        return Result.ok(current.shareCount);
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND }

    public record Result(Status status, int shareCount) {
        static Result ok(int count) { return new Result(Status.OK, count); }
        static Result userNotProvisioned() { return new Result(Status.USER_NOT_PROVISIONED, 0); }
        static Result notFound() { return new Result(Status.NOT_FOUND, 0); }
    }
}
