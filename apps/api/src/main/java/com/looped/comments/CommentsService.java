package com.looped.comments;

import com.looped.communities.CommunityVerificationsRepository;
import com.looped.posts.PostRepository;
import com.looped.shared.Pagination;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class CommentsService {
    private final CommentsRepository comments;
    private final PostRepository posts;
    private final UserRepository users;
    private final CommunityVerificationsRepository communityVerifications;

    public CommentsService(CommentsRepository comments, PostRepository posts, UserRepository users, CommunityVerificationsRepository communityVerifications) {
        this.comments = comments;
        this.posts = posts;
        this.users = users;
        this.communityVerifications = communityVerifications;
    }

    public ListResult list(String firebaseUid, long postId, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return ListResult.userNotProvisioned();

        var post = posts.findById(postId);
        if (post.isEmpty()) return ListResult.postNotFound();
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        var rows = comments.findByPost(postId, actor.get().id, post.get().authorId, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1).comment;
            next = Pagination.encode(last.createdAt, last.id);
        }
        return ListResult.ok(rows, next);
    }

    @Transactional
    public CreateResult create(String firebaseUid, long postId, String content, Long parentId) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return CreateResult.userNotProvisioned();

        var post = posts.findById(postId);
        if (post.isEmpty()) return CreateResult.postNotFound();
        if (post.get().communityId == null) return CreateResult.communityNotFound();
        if (!communityVerifications.isVerified(actor.get().id, post.get().communityId)) {
            return CreateResult.notVerified();
        }

        if (parentId != null) {
            var parent = comments.findById(parentId);
            if (parent.isEmpty()) return CreateResult.parentNotFound();
            if (parent.get().postId != postId) return CreateResult.invalidParent();
        }

        var inserted = comments.insert(postId, actor.get().id, actor.get().companyId, content, parentId);
        posts.incrementCommentsCount(postId);

        var view = comments.findViewById(inserted.id, actor.get().id, post.get().authorId).orElseThrow();
        return CreateResult.ok(view);
    }

    public RepliesResult replies(String firebaseUid, long commentId, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return RepliesResult.userNotProvisioned();

        var parent = comments.findById(commentId);
        if (parent.isEmpty()) return RepliesResult.commentNotFound();
        var post = posts.findById(parent.get().postId);
        if (post.isEmpty()) return RepliesResult.commentNotFound();
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        var rows = comments.findReplies(post.get().id, parent.get().id, actor.get().id, post.get().authorId, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1).comment;
            next = Pagination.encode(last.createdAt, last.id);
        }
        return RepliesResult.ok(rows, next);
    }

    public RepliesResult userReplies(String firebaseUid, long targetUserId, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return RepliesResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return RepliesResult.userNotFound();
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        var rows = comments.findByUserWithView(targetUserId, actor.get().id, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1).comment;
            next = Pagination.encode(last.createdAt, last.id);
        }
        return RepliesResult.ok(rows, next);
    }

    @Transactional
    public LikeResult like(String firebaseUid, long commentId) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return LikeResult.userNotProvisioned();

        var comment = comments.findById(commentId);
        if (comment.isEmpty()) return LikeResult.commentNotFound();
        var post = posts.findById(comment.get().postId);
        if (post.isEmpty()) return LikeResult.commentNotFound();
        boolean created = comments.insertLikeIfAbsent(commentId, actor.get().id);
        if (created) {
            comments.incrementCommentLikes(commentId);
        }
        var view = comments.findViewById(commentId, actor.get().id, post.get().authorId).orElseThrow();
        return LikeResult.ok(created, view.comment.likesCount, view.likedByCreator, view.viewerLiked);
    }

    public enum Status {
        OK,
        USER_NOT_PROVISIONED,
        POST_NOT_FOUND,
        COMMENT_NOT_FOUND,
        USER_NOT_FOUND,
        INVALID_PARENT,
        COMMUNITY_NOT_FOUND,
        NOT_VERIFIED
    }

    public record ListResult(Status status, List<CommentsRepository.CommentViewRow> comments, String nextCursor) {
        static ListResult ok(List<CommentsRepository.CommentViewRow> comments, String nextCursor) { return new ListResult(Status.OK, comments, nextCursor); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static ListResult postNotFound() { return new ListResult(Status.POST_NOT_FOUND, List.of(), null); }
    }

    public record CreateResult(Status status, CommentsRepository.CommentViewRow comment) {
        static CreateResult ok(CommentsRepository.CommentViewRow comment) { return new CreateResult(Status.OK, comment); }
        static CreateResult userNotProvisioned() { return new CreateResult(Status.USER_NOT_PROVISIONED, null); }
        static CreateResult postNotFound() { return new CreateResult(Status.POST_NOT_FOUND, null); }
        static CreateResult parentNotFound() { return new CreateResult(Status.COMMENT_NOT_FOUND, null); }
        static CreateResult invalidParent() { return new CreateResult(Status.INVALID_PARENT, null); }
        static CreateResult communityNotFound() { return new CreateResult(Status.COMMUNITY_NOT_FOUND, null); }
        static CreateResult notVerified() { return new CreateResult(Status.NOT_VERIFIED, null); }
    }

    public record RepliesResult(Status status, List<CommentsRepository.CommentViewRow> comments, String nextCursor) {
        static RepliesResult ok(List<CommentsRepository.CommentViewRow> comments, String nextCursor) { return new RepliesResult(Status.OK, comments, nextCursor); }
        static RepliesResult userNotProvisioned() { return new RepliesResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static RepliesResult commentNotFound() { return new RepliesResult(Status.COMMENT_NOT_FOUND, List.of(), null); }
        static RepliesResult userNotFound() { return new RepliesResult(Status.USER_NOT_FOUND, List.of(), null); }
    }

    public record LikeResult(Status status, boolean created, int likesCount, boolean likedByCreator, boolean userLiked) {
        static LikeResult ok(boolean created, int likesCount, boolean likedByCreator, boolean userLiked) { return new LikeResult(Status.OK, created, likesCount, likedByCreator, userLiked); }
        static LikeResult userNotProvisioned() { return new LikeResult(Status.USER_NOT_PROVISIONED, false, 0, false, false); }
        static LikeResult commentNotFound() { return new LikeResult(Status.COMMENT_NOT_FOUND, false, 0, false, false); }
    }
}
