package com.looped.posts;

import com.looped.shared.Pagination;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class PostCollectionsService {
    private final UserRepository users;
    private final PostRepository posts;
    private final LikesRepository likes;
    private final SavedPostsRepository savedPosts;

    public PostCollectionsService(UserRepository users, PostRepository posts, LikesRepository likes, SavedPostsRepository savedPosts) {
        this.users = users;
        this.posts = posts;
        this.likes = likes;
        this.savedPosts = savedPosts;
    }

    public ListResult liked(String firebaseUid, String cursor, int limit) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();

        var cursorParts = decodeCursor(cursor);
        var rows = likes.findLikedPosts(actor.get().id, actor.get().companyId, cursorParts.timestamp, cursorParts.postId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.likedAt, last.post.id);
        }
        List<PostRepository.PostRow> posts = rows.stream().map(r -> r.post).toList();
        return ListResult.ok(posts, next);
    }

    public ListResult saved(String firebaseUid, String cursor, int limit) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();

        var cursorParts = decodeCursor(cursor);
        var rows = savedPosts.findSavedPosts(actor.get().id, actor.get().companyId, cursorParts.timestamp, cursorParts.postId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.savedAt, last.post.id);
        }
        List<PostRepository.PostRow> posts = rows.stream().map(r -> r.post).toList();
        return ListResult.ok(posts, next);
    }

    public ListResult savedForUser(String firebaseUid, long targetUserId, String cursor, int limit) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return ListResult.notFound();
        if (!actor.get().companyId.equals(target.get().companyId)) return ListResult.forbidden();

        var cursorParts = decodeCursor(cursor);
        var rows = savedPosts.findSavedPosts(targetUserId, actor.get().companyId, cursorParts.timestamp, cursorParts.postId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.savedAt, last.post.id);
        }
        List<PostRepository.PostRow> posts = rows.stream().map(r -> r.post).toList();
        return ListResult.ok(posts, next);
    }

    public SaveResult save(String firebaseUid, long postId) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return SaveResult.userNotProvisioned();

        var post = posts.findById(postId);
        if (post.isEmpty()) return SaveResult.notFound(false);
        if (!actor.get().companyId.equals(post.get().companyId)) return SaveResult.forbidden(false);

        boolean created = savedPosts.insertIfAbsent(actor.get().id, postId);
        return SaveResult.ok(true, created);
    }

    public SaveResult unsave(String firebaseUid, long postId) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return SaveResult.userNotProvisioned();

        var post = posts.findById(postId);
        if (post.isEmpty()) return SaveResult.notFound(false);
        if (!actor.get().companyId.equals(post.get().companyId)) return SaveResult.forbidden(false);

        boolean deleted = savedPosts.delete(actor.get().id, postId);
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

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN }

    public record ListResult(Status status, List<PostRepository.PostRow> posts, String nextCursor) {
        static ListResult ok(List<PostRepository.PostRow> posts, String next) { return new ListResult(Status.OK, posts, next); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static ListResult notFound() { return new ListResult(Status.NOT_FOUND, List.of(), null); }
        static ListResult forbidden() { return new ListResult(Status.FORBIDDEN, List.of(), null); }
    }

    public record SaveResult(Status status, boolean saved, boolean changed) {
        static SaveResult ok(boolean saved, boolean changed) { return new SaveResult(Status.OK, saved, changed); }
        static SaveResult userNotProvisioned() { return new SaveResult(Status.USER_NOT_PROVISIONED, false, false); }
        static SaveResult notFound(boolean saved) { return new SaveResult(Status.NOT_FOUND, saved, false); }
        static SaveResult forbidden(boolean saved) { return new SaveResult(Status.FORBIDDEN, saved, false); }
    }
}
