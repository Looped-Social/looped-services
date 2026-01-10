package com.looped.posts;

import com.looped.anon.AnonProofService;
import com.looped.principals.PrincipalRepository;
import com.looped.shared.Pagination;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class PostCollectionsService {
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final PostRepository posts;
    private final LikesRepository likes;
    private final SavedPostsRepository savedPosts;
    private final RepostsRepository reposts;
    private final AnonProofService anonProofs;
    private final PostStateService postState;

    public PostCollectionsService(UserRepository users,
                                  PrincipalRepository principals,
                                  PostRepository posts,
                                  LikesRepository likes,
                                  SavedPostsRepository savedPosts,
                                  RepostsRepository reposts,
                                  AnonProofService anonProofs,
                                  PostStateService postState) {
        this.users = users;
        this.principals = principals;
        this.posts = posts;
        this.likes = likes;
        this.savedPosts = savedPosts;
        this.reposts = reposts;
        this.anonProofs = anonProofs;
        this.postState = postState;
    }

    public ListResult liked(String firebaseUid, String cursor, int limit) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();
        var principal = principals.createForUser(actor.get().id);

        var cursorParts = decodeCursor(cursor);
        var rows = likes.findLikedPosts(principal.id, cursorParts.timestamp, cursorParts.postId, limit,
                actor.get().id, actor.get().hideAnonymousPosts);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.likedAt, last.post.id);
        }
        List<PostRepository.PostRow> posts = rows.stream().map(r -> r.post).toList();
        postState.applyForPrincipal(principal.id, posts);
        return ListResult.ok(posts, next);
    }

    public ListResult saved(String firebaseUid, String cursor, int limit) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();
        var principal = principals.createForUser(actor.get().id);

        var cursorParts = decodeCursor(cursor);
        var rows = savedPosts.findSavedPosts(principal.id, cursorParts.timestamp, cursorParts.postId, limit,
                actor.get().id, actor.get().hideAnonymousPosts);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.savedAt, last.post.id);
        }
        List<PostRepository.PostRow> posts = rows.stream().map(r -> r.post).toList();
        postState.applyForPrincipal(principal.id, posts);
        return ListResult.ok(posts, next);
    }

    public ListResult reposted(String firebaseUid, String cursor, int limit) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();
        var principal = principals.createForUser(actor.get().id);

        var cursorParts = decodeCursor(cursor);
        var rows = reposts.repostedPosts(principal.id, cursorParts.timestamp, cursorParts.postId, limit,
                actor.get().id, actor.get().hideAnonymousPosts);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.repostedAt(), last.repostId());
        }
        List<PostRepository.PostRow> posts = rows.stream().map(RepostsRepository.RepostedPostRow::post).toList();
        postState.applyForPrincipal(principal.id, posts);
        return ListResult.ok(posts, next);
    }

    public ListResult savedForUser(String firebaseUid, long targetUserId, String cursor, int limit) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return ListResult.notFound();

        var targetPrincipal = principals.createForUser(targetUserId);

        var cursorParts = decodeCursor(cursor);
        var rows = savedPosts.findSavedPosts(targetPrincipal.id, cursorParts.timestamp, cursorParts.postId, limit,
                actor.get().id, actor.get().hideAnonymousPosts);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.savedAt, last.post.id);
        }
        List<PostRepository.PostRow> posts = rows.stream().map(r -> r.post).toList();
        var viewerPrincipal = principals.createForUser(actor.get().id);
        postState.applyForPrincipal(viewerPrincipal.id, posts);
        return ListResult.ok(posts, next);
    }

    public ListResult repostedForUser(String firebaseUid, long targetUserId, String cursor, int limit) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return ListResult.notFound();
        if (!actor.get().companyId.equals(target.get().companyId)) return ListResult.notFound();

        var targetPrincipal = principals.createForUser(targetUserId);

        var cursorParts = decodeCursor(cursor);
        var rows = reposts.repostedPosts(targetPrincipal.id, cursorParts.timestamp, cursorParts.postId, limit,
                actor.get().id, actor.get().hideAnonymousPosts);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.repostedAt(), last.repostId());
        }
        List<PostRepository.PostRow> posts = rows.stream().map(RepostsRepository.RepostedPostRow::post).toList();
        var viewerPrincipal = principals.createForUser(actor.get().id);
        postState.applyForPrincipal(viewerPrincipal.id, posts);
        return ListResult.ok(posts, next);
    }

    public SaveResult save(String firebaseUid, long postId, AnonProofService.AnonActionProof anonProof) {
        var post = posts.findById(postId);
        if (post.isEmpty()) return SaveResult.notFound(false);

        long actorPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            var verified = anonProofs.verifyActionScoped(anonProof, "save", postId, post.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return SaveResult.invalidSignature();
            actorPrincipalId = verified.actor().principalId();
        } else {
            var actor = provisionedUser(firebaseUid);
            if (actor.isEmpty()) return SaveResult.userNotProvisioned();
            var principal = principals.createForUser(actor.get().id);
            actorPrincipalId = principal.id;
        }

        boolean created = savedPosts.insertIfAbsent(actorPrincipalId, postId);
        return SaveResult.ok(true, created);
    }

    public SaveResult unsave(String firebaseUid, long postId, AnonProofService.AnonActionProof anonProof) {
        var post = posts.findById(postId);
        if (post.isEmpty()) return SaveResult.notFound(false);

        long actorPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            var verified = anonProofs.verifyActionScoped(anonProof, "unsave", postId, post.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return SaveResult.invalidSignature();
            actorPrincipalId = verified.actor().principalId();
        } else {
            var actor = provisionedUser(firebaseUid);
            if (actor.isEmpty()) return SaveResult.userNotProvisioned();
            var principal = principals.createForUser(actor.get().id);
            actorPrincipalId = principal.id;
        }

        boolean deleted = savedPosts.delete(actorPrincipalId, postId);
        return SaveResult.ok(false, deleted);
    }

    private Optional<UserRepository.UserRow> provisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
    }

    private CursorParts decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new CursorParts(null, null);
        }
        try {
            var decoded = Pagination.decode(cursor);
            return new CursorParts(decoded.timestamp(), decoded.id());
        } catch (IllegalArgumentException ignored) {
            return new CursorParts(null, null);
        }
    }

    private record CursorParts(OffsetDateTime timestamp, Long postId) {}

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, INVALID_SIGNATURE }

    public record ListResult(Status status, List<PostRepository.PostRow> posts, String nextCursor) {
        static ListResult ok(List<PostRepository.PostRow> posts, String next) { return new ListResult(Status.OK, posts, next); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static ListResult notFound() { return new ListResult(Status.NOT_FOUND, List.of(), null); }
    }

    public record SaveResult(Status status, boolean saved, boolean changed) {
        static SaveResult ok(boolean saved, boolean changed) { return new SaveResult(Status.OK, saved, changed); }
        static SaveResult userNotProvisioned() { return new SaveResult(Status.USER_NOT_PROVISIONED, false, false); }
        static SaveResult notFound(boolean saved) { return new SaveResult(Status.NOT_FOUND, saved, false); }
        static SaveResult invalidSignature() { return new SaveResult(Status.INVALID_SIGNATURE, false, false); }
    }
}
