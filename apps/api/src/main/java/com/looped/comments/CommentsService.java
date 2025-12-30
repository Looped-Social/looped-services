package com.looped.comments;

import com.looped.anon.AnonProofService;
import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.notifications.NotificationPublisher;
import com.looped.principals.PrincipalRepository;
import com.looped.posts.PostRepository;
import com.looped.shared.MentionParser;
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
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final PrincipalRepository principals;
    private final AnonProofService anonProofs;
    private final NotificationPublisher notifications;

    public CommentsService(CommentsRepository comments,
                           PostRepository posts,
                           UserRepository users,
                           CommunitiesRepository communities,
                           CommunityVerificationsRepository communityVerifications,
                           PrincipalRepository principals,
                           AnonProofService anonProofs,
                           NotificationPublisher notifications) {
        this.comments = comments;
        this.posts = posts;
        this.users = users;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.principals = principals;
        this.anonProofs = anonProofs;
        this.notifications = notifications;
    }

    public ListResult list(String firebaseUid, long postId, String cursor, int limit, AnonProofService.AnonActionProof anonProof) {
        java.util.Optional<UserRepository.UserRow> actor = java.util.Optional.empty();
        if (anonProof == null) {
            if (firebaseUid == null) return ListResult.userNotProvisioned();
            actor = users.findByFirebaseUid(firebaseUid);
            if (actor.isEmpty() || actor.get().companyId == null) return ListResult.userNotProvisioned();
        }

        var post = posts.findById(postId);
        if (post.isEmpty()) return ListResult.postNotFound();
        long viewerPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            if (post.get().communityId == null) return ListResult.invalidAnonProof();
            var verified = anonProofs.verifyActionScoped(anonProof, "comment_list", postId, post.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return ListResult.invalidAnonProof();
            viewerPrincipalId = verified.actor().principalId();
        } else {
            viewerPrincipalId = principals.createForUser(actor.orElseThrow().id).id;
        }
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        var rows = comments.findByPost(postId, viewerPrincipalId, post.get().authorPrincipalId, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1).comment;
            next = Pagination.encode(last.createdAt, last.id);
        }
        return ListResult.ok(rows, next);
    }

    @Transactional
    public CreateResult create(String firebaseUid, long postId, String content, Long parentId, AnonProofService.AnonActionProof anonProof) {
        var post = posts.findById(postId);
        if (post.isEmpty()) return CreateResult.postNotFound();
        if (post.get().communityId == null) return CreateResult.communityNotFound();

        long actorPrincipalId;
        Long actorUserId = null;
        long effectiveCompanyId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            var verified = anonProofs.verifyActionScoped(anonProof, "comment", postId, post.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return CreateResult.invalidAnonProof();
            actorPrincipalId = verified.actor().principalId();
            if (verified.actor().companyId() == null) return CreateResult.invalidAnonProof();
            effectiveCompanyId = verified.actor().companyId();
        } else {
            if (firebaseUid == null) return CreateResult.userNotProvisioned();
            var actor = users.findByFirebaseUid(firebaseUid);
            if (actor.isEmpty() || actor.get().companyId == null) return CreateResult.userNotProvisioned();
            if (requiresVerification(post.get().communityId)
                    && !communityVerifications.isVerified(actor.get().id, post.get().communityId)) {
                return CreateResult.notVerified();
            }
            actorPrincipalId = principals.createForUser(actor.get().id).id;
            actorUserId = actor.get().id;
            effectiveCompanyId = actor.get().companyId;
        }

        if (parentId != null) {
            var parent = comments.findById(parentId);
            if (parent.isEmpty()) return CreateResult.parentNotFound();
            if (parent.get().postId != postId) return CreateResult.invalidParent();
        }

        var inserted = comments.insert(postId, actorUserId, actorPrincipalId, effectiveCompanyId, content, parentId);
        posts.incrementCommentsCount(postId);

        var view = comments.findViewById(inserted.id, actorPrincipalId, post.get().authorPrincipalId).orElseThrow();
        try {
            notifications.notifyComment(post.get(), inserted.id, actorPrincipalId);
            notifyMentions(actorPrincipalId, actorUserId, effectiveCompanyId, content, postId, inserted.id, post.get().authorId);
        } catch (RuntimeException ignored) {}
        return CreateResult.ok(view);
    }

    public RepliesResult replies(String firebaseUid, long commentId, String cursor, int limit, AnonProofService.AnonActionProof anonProof) {
        java.util.Optional<UserRepository.UserRow> actor = java.util.Optional.empty();
        if (anonProof == null) {
            if (firebaseUid == null) return RepliesResult.userNotProvisioned();
            actor = users.findByFirebaseUid(firebaseUid);
            if (actor.isEmpty() || actor.get().companyId == null) return RepliesResult.userNotProvisioned();
        }

        var parent = comments.findById(commentId);
        if (parent.isEmpty()) return RepliesResult.commentNotFound();
        var post = posts.findById(parent.get().postId);
        if (post.isEmpty()) return RepliesResult.commentNotFound();
        long viewerPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            if (post.get().communityId == null) return RepliesResult.invalidAnonProof();
            var verified = anonProofs.verifyActionScoped(anonProof, "comment_replies", commentId, post.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return RepliesResult.invalidAnonProof();
            viewerPrincipalId = verified.actor().principalId();
        } else {
            viewerPrincipalId = principals.createForUser(actor.orElseThrow().id).id;
        }
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        var rows = comments.findReplies(post.get().id, parent.get().id, viewerPrincipalId, post.get().authorPrincipalId, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1).comment;
            next = Pagination.encode(last.createdAt, last.id);
        }
        return RepliesResult.ok(rows, next);
    }

    private boolean requiresVerification(Long communityId) {
        if (communityId == null) return false;
        var community = communities.findById(communityId);
        return community.isPresent() && !"specialization".equalsIgnoreCase(community.get().kind);
    }

    public RepliesResult userReplies(String firebaseUid, long targetUserId, String cursor, int limit, AnonProofService.AnonActionProof anonProof) {
        java.util.Optional<UserRepository.UserRow> actor = java.util.Optional.empty();
        if (anonProof == null) {
            if (firebaseUid == null) return RepliesResult.userNotProvisioned();
            actor = users.findByFirebaseUid(firebaseUid);
            if (actor.isEmpty() || actor.get().companyId == null) return RepliesResult.userNotProvisioned();
        }

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return RepliesResult.userNotFound();
        long viewerPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            var verified = anonProofs.verifyAction(anonProof, "comment_user_replies", targetUserId);
            if (verified.status() != AnonProofService.Status.OK) return RepliesResult.invalidAnonProof();
            viewerPrincipalId = verified.actor().principalId();
        } else {
            viewerPrincipalId = principals.createForUser(actor.orElseThrow().id).id;
        }
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        var rows = comments.findByUserWithView(targetUserId, viewerPrincipalId, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1).comment;
            next = Pagination.encode(last.createdAt, last.id);
        }
        return RepliesResult.ok(rows, next);
    }

    @Transactional
    public LikeResult like(String firebaseUid, long commentId, AnonProofService.AnonActionProof anonProof) {
        var comment = comments.findById(commentId);
        if (comment.isEmpty()) return LikeResult.commentNotFound();
        var post = posts.findById(comment.get().postId);
        if (post.isEmpty()) return LikeResult.commentNotFound();
        long actorPrincipalId;
        Long actorUserId = null;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            if (post.get().communityId == null) return LikeResult.invalidAnonProof();
            var verified = anonProofs.verifyActionScoped(anonProof, "comment_like", commentId, post.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return LikeResult.invalidAnonProof();
            actorPrincipalId = verified.actor().principalId();
        } else {
            if (firebaseUid == null) return LikeResult.userNotProvisioned();
            var actor = users.findByFirebaseUid(firebaseUid);
            if (actor.isEmpty() || actor.get().companyId == null) return LikeResult.userNotProvisioned();
            actorPrincipalId = principals.createForUser(actor.get().id).id;
            actorUserId = actor.get().id;
        }
        boolean created = comments.insertLikeIfAbsent(commentId, actorPrincipalId, actorUserId);
        if (created) {
            comments.incrementCommentLikes(commentId);
        }
        var view = comments.findViewById(commentId, actorPrincipalId, post.get().authorPrincipalId).orElseThrow();
        return LikeResult.ok(created, view.comment.likesCount, view.likedByCreator, view.viewerLiked);
    }

    private void notifyMentions(long actorPrincipalId, Long actorUserId, long companyId, String content, Long postId, Long commentId, Long skipUserId) {
        var handles = MentionParser.extract(content);
        if (handles.isEmpty()) return;
        var mentioned = users.findByHandlesInCompany(companyId, handles);
        if (mentioned.isEmpty()) return;
        java.util.List<Long> userIds = mentioned.stream()
                .map(u -> u.id)
                .filter(id -> actorUserId == null || id != actorUserId)
                .filter(id -> skipUserId == null || id != skipUserId)
                .distinct()
                .toList();
        notifications.notifyMentions(actorPrincipalId, userIds, postId, commentId);
    }

    public enum Status {
        OK,
        USER_NOT_PROVISIONED,
        POST_NOT_FOUND,
        COMMENT_NOT_FOUND,
        USER_NOT_FOUND,
        INVALID_PARENT,
        COMMUNITY_NOT_FOUND,
        NOT_VERIFIED,
        INVALID_ANON_PROOF
    }

    public record ListResult(Status status, List<CommentsRepository.CommentViewRow> comments, String nextCursor) {
        static ListResult ok(List<CommentsRepository.CommentViewRow> comments, String nextCursor) { return new ListResult(Status.OK, comments, nextCursor); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static ListResult postNotFound() { return new ListResult(Status.POST_NOT_FOUND, List.of(), null); }
        static ListResult invalidAnonProof() { return new ListResult(Status.INVALID_ANON_PROOF, List.of(), null); }
    }

    public record CreateResult(Status status, CommentsRepository.CommentViewRow comment) {
        static CreateResult ok(CommentsRepository.CommentViewRow comment) { return new CreateResult(Status.OK, comment); }
        static CreateResult userNotProvisioned() { return new CreateResult(Status.USER_NOT_PROVISIONED, null); }
        static CreateResult postNotFound() { return new CreateResult(Status.POST_NOT_FOUND, null); }
        static CreateResult parentNotFound() { return new CreateResult(Status.COMMENT_NOT_FOUND, null); }
        static CreateResult invalidParent() { return new CreateResult(Status.INVALID_PARENT, null); }
        static CreateResult communityNotFound() { return new CreateResult(Status.COMMUNITY_NOT_FOUND, null); }
        static CreateResult notVerified() { return new CreateResult(Status.NOT_VERIFIED, null); }
        static CreateResult invalidAnonProof() { return new CreateResult(Status.INVALID_ANON_PROOF, null); }
    }

    public record RepliesResult(Status status, List<CommentsRepository.CommentViewRow> comments, String nextCursor) {
        static RepliesResult ok(List<CommentsRepository.CommentViewRow> comments, String nextCursor) { return new RepliesResult(Status.OK, comments, nextCursor); }
        static RepliesResult userNotProvisioned() { return new RepliesResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static RepliesResult commentNotFound() { return new RepliesResult(Status.COMMENT_NOT_FOUND, List.of(), null); }
        static RepliesResult userNotFound() { return new RepliesResult(Status.USER_NOT_FOUND, List.of(), null); }
        static RepliesResult invalidAnonProof() { return new RepliesResult(Status.INVALID_ANON_PROOF, List.of(), null); }
    }

    public record LikeResult(Status status, boolean created, int likesCount, boolean likedByCreator, boolean userLiked) {
        static LikeResult ok(boolean created, int likesCount, boolean likedByCreator, boolean userLiked) { return new LikeResult(Status.OK, created, likesCount, likedByCreator, userLiked); }
        static LikeResult userNotProvisioned() { return new LikeResult(Status.USER_NOT_PROVISIONED, false, 0, false, false); }
        static LikeResult commentNotFound() { return new LikeResult(Status.COMMENT_NOT_FOUND, false, 0, false, false); }
        static LikeResult invalidAnonProof() { return new LikeResult(Status.INVALID_ANON_PROOF, false, 0, false, false); }
    }
}
