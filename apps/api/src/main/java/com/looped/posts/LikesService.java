package com.looped.posts;

import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LikesService {
    private final LikesRepository likes;
    private final PostRepository posts;
    private final UserRepository users;

    public LikesService(LikesRepository likes, PostRepository posts, UserRepository users) {
        this.likes = likes;
        this.posts = posts;
        this.users = users;
    }

    @Transactional
    public Result like(String firebaseUid, long postId) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return Result.userNotProvisioned();
        var p = posts.findById(postId);
        if (p.isEmpty()) return Result.notFound();
        if (u.get().companyId == null || p.get().companyId != u.get().companyId) return Result.forbidden();

        boolean created = likes.insertIfAbsent(u.get().id, postId);
        if (created) {
            likes.incrementPostLikes(postId);
        }
        var current = posts.findById(postId).orElseThrow();
        return Result.ok(created, current.likesCount);
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN }
    public record Result(Status status, boolean created, int likesCount) {
        static Result ok(boolean created, int count) { return new Result(Status.OK, created, count); }
        static Result userNotProvisioned() { return new Result(Status.USER_NOT_PROVISIONED, false, 0); }
        static Result notFound() { return new Result(Status.NOT_FOUND, false, 0); }
        static Result forbidden() { return new Result(Status.FORBIDDEN, false, 0); }
    }
}
