package com.looped.posts;

import com.looped.anon.AnonProofService;
import com.looped.notifications.NotificationPublisher;
import com.looped.principals.PrincipalRepository;
import com.looped.shared.Pagination;
import com.looped.users.BlocksRepository;
import com.looped.users.UserCommunityBanRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class RepostsService {
    private final PostRepository posts;
    private final RepostsRepository reposts;
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final BlocksRepository blocks;
    private final UserCommunityBanRepository communityBans;
    private final AnonProofService anonProofs;
    private final NotificationPublisher notifications;

    public RepostsService(PostRepository posts,
                          RepostsRepository reposts,
                          UserRepository users,
                          PrincipalRepository principals,
                          BlocksRepository blocks,
                          UserCommunityBanRepository communityBans,
                          AnonProofService anonProofs,
                          NotificationPublisher notifications) {
        this.posts = posts;
        this.reposts = reposts;
        this.users = users;
        this.principals = principals;
        this.blocks = blocks;
        this.communityBans = communityBans;
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

    public RepostersResult reposters(String firebaseUid, long postId, String cursor, int limit) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty()) return RepostersResult.userNotProvisioned();
        var post = posts.findById(postId);
        if (post.isEmpty()) return RepostersResult.notFound();

        var principal = principals.createForUser(user.get().id);
        if (post.get().visibility != null && !post.get().visibility.equalsIgnoreCase("public")
                && principal.id != post.get().authorPrincipalId) {
            return RepostersResult.notFound();
        }
        if (blocks.existsEitherDirection(principal.id, post.get().authorPrincipalId)) {
            return RepostersResult.notFound();
        }
        if (user.get().hideAnonymousPosts && post.get().authorIsAnonymous
                && (post.get().authorId == null || post.get().authorId != user.get().id)) {
            return RepostersResult.notFound();
        }
        if (post.get().communityId != null && communityBans.isBanned(user.get().id, post.get().communityId)) {
            return RepostersResult.communityBanned();
        }

        OffsetDateTime cTs = null;
        Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {
                // treat as no cursor
            }
        }
        List<RepostsRepository.PostReposterRow> repostersRows = reposts.repostersForPost(
                postId,
                principal.id,
                cTs,
                cId,
                limit
        );
        String next = null;
        if (repostersRows.size() == limit) {
            var last = repostersRows.get(repostersRows.size() - 1);
            next = Pagination.encode(last.repostedAt(), last.repostId());
        }
        return RepostersResult.ok(repostersRows, next);
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, INVALID_SIGNATURE, SELF_REPOST_NOT_ALLOWED }

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

        static ToggleResult selfRepostNotAllowed() {
            return new ToggleResult(Status.SELF_REPOST_NOT_ALLOWED, false, false, 0);
        }
    }

    public enum RepostersStatus { OK, USER_NOT_PROVISIONED, NOT_FOUND, COMMUNITY_BANNED }

    public record RepostersResult(RepostersStatus status, List<RepostsRepository.PostReposterRow> reposters, String nextCursor) {
        static RepostersResult ok(List<RepostsRepository.PostReposterRow> reposters, String nextCursor) {
            return new RepostersResult(RepostersStatus.OK, reposters, nextCursor);
        }

        static RepostersResult userNotProvisioned() {
            return new RepostersResult(RepostersStatus.USER_NOT_PROVISIONED, List.of(), null);
        }

        static RepostersResult notFound() {
            return new RepostersResult(RepostersStatus.NOT_FOUND, List.of(), null);
        }

        static RepostersResult communityBanned() {
            return new RepostersResult(RepostersStatus.COMMUNITY_BANNED, List.of(), null);
        }
    }
}
