package com.looped.posts;

import com.looped.anon.AnonProofService;
import com.looped.principals.PrincipalRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LikesService {
    private final LikesRepository likes;
    private final PostRepository posts;
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final AnonProofService anonProofs;

    public LikesService(LikesRepository likes,
                        PostRepository posts,
                        UserRepository users,
                        PrincipalRepository principals,
                        AnonProofService anonProofs) {
        this.likes = likes;
        this.posts = posts;
        this.users = users;
        this.principals = principals;
        this.anonProofs = anonProofs;
    }

    @Transactional
    public Result like(String firebaseUid, long postId, AnonProofService.AnonActionProof anonProof) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return Result.userNotProvisioned();
        var p = posts.findById(postId);
        if (p.isEmpty()) return Result.notFound();

        long actorPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            var verified = anonProofs.verifyAction(anonProof, "like", postId);
            if (verified.status() != AnonProofService.Status.OK) return Result.invalidSignature();
            actorPrincipalId = verified.actor().principalId();
        } else {
            var principal = principals.createForUser(u.get().id);
            actorPrincipalId = principal.id;
        }

        boolean created = likes.insertIfAbsent(actorPrincipalId, postId);
        if (created) {
            likes.incrementPostLikes(postId);
        }
        var current = posts.findById(postId).orElseThrow();
        return Result.ok(created, current.likesCount);
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, INVALID_SIGNATURE }
    public record Result(Status status, boolean created, int likesCount) {
        static Result ok(boolean created, int count) { return new Result(Status.OK, created, count); }
        static Result userNotProvisioned() { return new Result(Status.USER_NOT_PROVISIONED, false, 0); }
        static Result notFound() { return new Result(Status.NOT_FOUND, false, 0); }
        static Result invalidSignature() { return new Result(Status.INVALID_SIGNATURE, false, 0); }
    }
}
