package com.looped.posts;

import com.looped.principals.PrincipalRepository;
import com.looped.users.UserCommunityBanRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostSharesService {
    private final PostRepository posts;
    private final PostSharesRepository shares;
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final UserCommunityBanRepository communityBans;

    public PostSharesService(PostRepository posts,
                             PostSharesRepository shares,
                             UserRepository users,
                             PrincipalRepository principals,
                             UserCommunityBanRepository communityBans) {
        this.posts = posts;
        this.shares = shares;
        this.users = users;
        this.principals = principals;
        this.communityBans = communityBans;
    }

    @Transactional
    public Result share(String firebaseUid, long postId) {
        var postOpt = posts.findById(postId);
        if (postOpt.isEmpty()) return Result.notFound();
        if (firebaseUid == null) return Result.userNotProvisioned();
        var userOpt = users.findByFirebaseUid(firebaseUid);
        if (userOpt.isEmpty()) return Result.userNotProvisioned();
        if (postOpt.get().communityId != null && communityBans.isBanned(userOpt.get().id, postOpt.get().communityId)) {
            return Result.communityBanned();
        }
        var principal = principals.createForUser(userOpt.get().id);
        shares.insert(principal.id, postId);
        shares.incrementPostShares(postId);
        var current = posts.findById(postId).orElseThrow();
        return Result.ok(current.shareCount);
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, COMMUNITY_BANNED }

    public record Result(Status status, int shareCount) {
        static Result ok(int count) { return new Result(Status.OK, count); }
        static Result userNotProvisioned() { return new Result(Status.USER_NOT_PROVISIONED, 0); }
        static Result notFound() { return new Result(Status.NOT_FOUND, 0); }
        static Result communityBanned() { return new Result(Status.COMMUNITY_BANNED, 0); }
    }
}
