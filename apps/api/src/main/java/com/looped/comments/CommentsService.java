package com.looped.comments;

import com.looped.anon.AnonProofService;
import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.SpecializationJoinsRepository;
import com.looped.media.MediaRepository;
import com.looped.moderation.ContentModerationService;
import com.looped.moderation.QuarantineService;
import com.looped.notifications.NotificationPublisher;
import com.looped.principals.PrincipalRepository;
import com.looped.posts.PostRepository;
import com.looped.shared.MentionParser;
import com.looped.shared.Pagination;
import com.looped.users.UserCommunityBanRepository;
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
    private final SpecializationJoinsRepository specializationJoins;
    private final PrincipalRepository principals;
    private final AnonProofService anonProofs;
    private final NotificationPublisher notifications;
    private final UserCommunityBanRepository communityBans;
    private final MediaRepository media;
    private final ContentModerationService contentModeration;
    private final QuarantineService quarantine;

    public CommentsService(CommentsRepository comments,
                           PostRepository posts,
                           UserRepository users,
                           CommunitiesRepository communities,
                           CommunityVerificationsRepository communityVerifications,
                           SpecializationJoinsRepository specializationJoins,
                           PrincipalRepository principals,
                           AnonProofService anonProofs,
                           NotificationPublisher notifications,
                           UserCommunityBanRepository communityBans,
                           MediaRepository media,
                           ContentModerationService contentModeration,
                           QuarantineService quarantine) {
        this.comments = comments;
        this.posts = posts;
        this.users = users;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.specializationJoins = specializationJoins;
        this.principals = principals;
        this.anonProofs = anonProofs;
        this.notifications = notifications;
        this.communityBans = communityBans;
        this.media = media;
        this.contentModeration = contentModeration;
        this.quarantine = quarantine;
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
        if (post.get().visibility != null && !post.get().visibility.equalsIgnoreCase("public")
                && viewerPrincipalId != post.get().authorPrincipalId) {
            return ListResult.postNotFound();
        }
        if (anonProof == null && post.get().communityId != null && communityBans.isBanned(actor.orElseThrow().id, post.get().communityId)) {
            return ListResult.communityBanned();
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

    public PublicListResult listPublic(long postId, String cursor, int limit) {
        var post = posts.findByIdIncludingRemoved(postId);
        if (post.isEmpty()) return PublicListResult.postNotFound();
        if (post.get().removedAt != null) return PublicListResult.postUnavailable();
        if (post.get().visibility == null || !post.get().visibility.equalsIgnoreCase("public")) {
            return PublicListResult.postUnavailable();
        }
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        long publicViewerPrincipalId = 0L;
        var rows = comments.findByPost(postId, publicViewerPrincipalId, post.get().authorPrincipalId, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1).comment;
            next = Pagination.encode(last.createdAt, last.id);
        }
        return PublicListResult.ok(rows, next);
    }

    @Transactional
    public CreateResult create(String firebaseUid, long postId, String content, Long mediaAssetId, Long parentId, AnonProofService.AnonActionProof anonProof) {
        var post = posts.findById(postId);
        if (post.isEmpty()) return CreateResult.postNotFound();
        if (post.get().communityId == null) return CreateResult.communityNotFound();

        long actorPrincipalId;
        Long actorUserId = null;
        long effectiveCompanyId;
        ContentModerationService.Decision decision;
        boolean isAnonAction = false;
        boolean mediaUnderReview = false;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            isAnonAction = true;
            var verified = anonProofs.verifyActionScoped(anonProof, "comment", postId, post.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return CreateResult.invalidAnonProof();
            actorPrincipalId = verified.actor().principalId();
            if (verified.actor().companyId() == null) return CreateResult.invalidAnonProof();
            effectiveCompanyId = verified.actor().companyId();
            if (mediaAssetId != null && !mediaOwnerIsNull(mediaAssetId)) {
                return CreateResult.anonMediaNotAllowed();
            }
            decision = contentModeration.evaluateTextForAnon(content);
        } else {
            if (firebaseUid == null) return CreateResult.userNotProvisioned();
            var actor = users.findByFirebaseUid(firebaseUid);
            if (actor.isEmpty() || actor.get().companyId == null) return CreateResult.userNotProvisioned();
            if (communityBans.isBanned(actor.get().id, post.get().communityId)) {
                return CreateResult.communityBanned();
            }
            AccessStatus access = checkAccess(actor.get().id, post.get().communityId);
            if (access == AccessStatus.NOT_VERIFIED) return CreateResult.notVerified();
            if (access == AccessStatus.SPECIALIZATION_NOT_JOINED) return CreateResult.specializationNotJoined();
            actorPrincipalId = principals.createForUser(actor.get().id).id;
            actorUserId = actor.get().id;
            effectiveCompanyId = actor.get().companyId;
            decision = contentModeration.evaluateText(content);
        }
        if (mediaAssetId != null) {
            var row = media.findById(mediaAssetId).orElse(null);
            if (row == null || row.removedAt != null) return CreateResult.mediaNotFound();
            if (row.visibility != null && !row.visibility.equalsIgnoreCase("public")) mediaUnderReview = true;
        }
        if (post.get().visibility != null && !post.get().visibility.equalsIgnoreCase("public")
                && actorPrincipalId != post.get().authorPrincipalId) {
            return CreateResult.postNotFound();
        }
        if (decision.action() == ContentModerationService.Action.REJECT_ANON) {
            return CreateResult.contentUnderReview();
        }
        if (mediaUnderReview && isAnonAction) {
            return CreateResult.contentUnderReview();
        }

        CommentsRepository.CommentRow parentComment = null;
        if (parentId != null) {
            var parent = comments.findById(parentId);
            if (parent.isEmpty()) return CreateResult.parentNotFound();
            if (parent.get().postId != postId) return CreateResult.invalidParent();
            parentComment = parent.get();
        }

        var inserted = comments.insert(postId, actorUserId, actorPrincipalId, effectiveCompanyId, content, mediaAssetId, parentId);
        posts.incrementCommentsCount(postId);
        if (parentId != null) {
            incrementReplyCountWithVisibilityPropagation(parentId);
        }
        boolean quarantineComment = decision.action() == ContentModerationService.Action.QUARANTINE || mediaUnderReview;
        if (quarantineComment) {
            if (decision.action() == ContentModerationService.Action.QUARANTINE) {
                quarantine.quarantineComment(inserted.id, decision.source(), decision.reason());
            } else {
                quarantine.quarantineComment(inserted.id, "media", "policy:media_under_review");
            }
        }

        var view = comments.findViewById(inserted.id, actorPrincipalId, post.get().authorPrincipalId).orElseThrow();
        if (!quarantineComment) {
            try {
                notifications.notifyComment(post.get(), inserted.id, actorPrincipalId);
                if (parentComment != null) {
                    notifications.notifyReply(parentComment, inserted.id, actorPrincipalId);
                }
                notifyMentions(actorPrincipalId, actorUserId, effectiveCompanyId, content, postId, inserted.id, post.get().authorId);
            } catch (RuntimeException ignored) {}
        }
        return CreateResult.ok(view);
    }

    private boolean mediaOwnerIsNull(long mediaAssetId) {
        Long ownerId = media.findOwnerId(mediaAssetId);
        return ownerId == null;
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
        if (post.get().visibility != null && !post.get().visibility.equalsIgnoreCase("public")
                && viewerPrincipalId != post.get().authorPrincipalId) {
            return RepliesResult.commentNotFound();
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

    public PublicRepliesResult repliesPublic(long commentId, String cursor, int limit) {
        var parent = comments.findById(commentId);
        if (parent.isEmpty()) return PublicRepliesResult.commentNotFound();
        var post = posts.findByIdIncludingRemoved(parent.get().postId);
        if (post.isEmpty()) return PublicRepliesResult.commentNotFound();
        if (post.get().removedAt != null) return PublicRepliesResult.postUnavailable();
        if (post.get().visibility == null || !post.get().visibility.equalsIgnoreCase("public")) {
            return PublicRepliesResult.postUnavailable();
        }

        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        long publicViewerPrincipalId = 0L;
        var rows = comments.findReplies(post.get().id, parent.get().id, publicViewerPrincipalId, post.get().authorPrincipalId, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1).comment;
            next = Pagination.encode(last.createdAt, last.id);
        }
        return PublicRepliesResult.ok(rows, next);
    }

    private AccessStatus checkAccess(long userId, Long communityId) {
        if (communityId == null) return AccessStatus.OK;
        var community = communities.findById(communityId).orElse(null);
        if (community == null || community.kind == null) return AccessStatus.OK;
        if ("specialization".equalsIgnoreCase(community.kind)) {
            if (!requiresSpecializationJoin(community.specializationType)) return AccessStatus.OK;
            return specializationJoins.exists(userId, communityId) ? AccessStatus.OK : AccessStatus.SPECIALIZATION_NOT_JOINED;
        }
        return communityVerifications.isVerified(userId, communityId) ? AccessStatus.OK : AccessStatus.NOT_VERIFIED;
    }

    private boolean requiresSpecializationJoin(String specializationType) {
        if (specializationType == null) return false;
        String t = specializationType.trim().toLowerCase(java.util.Locale.ROOT);
        return t.equals("major") || t.equals("field");
    }

    private enum AccessStatus { OK, NOT_VERIFIED, SPECIALIZATION_NOT_JOINED }

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
    public EditResult edit(String firebaseUid, long commentId, String content, AnonProofService.AnonActionProof anonProof) {
        var comment = comments.findById(commentId);
        if (comment.isEmpty()) return EditResult.commentNotFound();
        if (comment.get().deletedAt != null) return EditResult.commentDeleted();
        if (comment.get().removedAt != null) return EditResult.commentDeleted();
        var post = posts.findById(comment.get().postId);
        if (post.isEmpty()) return EditResult.commentNotFound();

        long actorPrincipalId;
        ContentModerationService.Decision decision;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            if (post.get().communityId == null) return EditResult.invalidAnonProof();
            var verified = anonProofs.verifyActionScoped(anonProof, "comment_edit", commentId, post.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return EditResult.invalidAnonProof();
            actorPrincipalId = verified.actor().principalId();
            decision = contentModeration.evaluateTextForAnon(content);
        } else {
            if (firebaseUid == null) return EditResult.userNotProvisioned();
            var actor = users.findByFirebaseUid(firebaseUid);
            if (actor.isEmpty() || actor.get().companyId == null) return EditResult.userNotProvisioned();
            actorPrincipalId = principals.createForUser(actor.get().id).id;
            decision = contentModeration.evaluateText(content);
        }

        if (actorPrincipalId != comment.get().authorPrincipalId) return EditResult.forbidden();
        if (post.get().visibility != null && !post.get().visibility.equalsIgnoreCase("public")
                && actorPrincipalId != post.get().authorPrincipalId) {
            return EditResult.commentNotFound();
        }
        if (decision.action() == ContentModerationService.Action.REJECT_ANON) {
            return EditResult.contentUnderReview();
        }

        boolean updated = comments.updateContent(commentId, content);
        if (!updated) return EditResult.commentDeleted();
        if (decision.action() == ContentModerationService.Action.QUARANTINE
                && (comment.get().visibility == null || comment.get().visibility.equalsIgnoreCase("public"))) {
            quarantine.quarantineComment(commentId, decision.source(), decision.reason());
        }

        var view = comments.findViewById(commentId, actorPrincipalId, post.get().authorPrincipalId).orElseThrow();
        return EditResult.ok(view);
    }

    @Transactional
    public DeleteResult delete(String firebaseUid, long commentId, AnonProofService.AnonActionProof anonProof) {
        var comment = comments.findByIdForUpdate(commentId);
        if (comment.isEmpty()) return DeleteResult.commentNotFound();
        if (comment.get().deletedAt != null) return DeleteResult.ok(false);
        if (comment.get().removedAt != null) return DeleteResult.ok(false);
        var post = posts.findById(comment.get().postId);
        if (post.isEmpty()) return DeleteResult.commentNotFound();

        long actorPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            if (post.get().communityId == null) return DeleteResult.invalidAnonProof();
            var verified = anonProofs.verifyActionScoped(anonProof, "comment_delete", commentId, post.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return DeleteResult.invalidAnonProof();
            actorPrincipalId = verified.actor().principalId();
        } else {
            if (firebaseUid == null) return DeleteResult.userNotProvisioned();
            var actor = users.findByFirebaseUid(firebaseUid);
            if (actor.isEmpty() || actor.get().companyId == null) return DeleteResult.userNotProvisioned();
            if (post.get().communityId != null && communityBans.isBanned(actor.get().id, post.get().communityId)) {
                return DeleteResult.communityBanned();
            }
            actorPrincipalId = principals.createForUser(actor.get().id).id;
        }
        if (post.get().visibility != null && !post.get().visibility.equalsIgnoreCase("public")
                && actorPrincipalId != post.get().authorPrincipalId) {
            return DeleteResult.commentNotFound();
        }

        if (actorPrincipalId != comment.get().authorPrincipalId) return DeleteResult.forbidden();

        boolean hadVisibleReplies = comments.hasVisibleReplies(commentId);
        boolean deleted = comments.softDelete(commentId);
        if (deleted) {
            posts.decrementCommentsCount(comment.get().postId);
            if (comment.get().parentId != null && !hadVisibleReplies) {
                decrementReplyCountWithVisibilityPropagation(comment.get().parentId);
            }
        }
        return DeleteResult.ok(deleted);
    }

    private void incrementReplyCountWithVisibilityPropagation(long parentId) {
        Long currentId = parentId;
        while (currentId != null) {
            var current = comments.findByIdForUpdate(currentId).orElse(null);
            if (current == null) return;
            int before = current.replyCount;
            comments.incrementReplyCount(currentId);

            boolean becameVisible = current.deletedAt != null && before == 0;
            if (!becameVisible || current.parentId == null) return;
            currentId = current.parentId;
        }
    }

    private void decrementReplyCountWithVisibilityPropagation(long parentId) {
        Long currentId = parentId;
        while (currentId != null) {
            var current = comments.findByIdForUpdate(currentId).orElse(null);
            if (current == null) return;
            int before = current.replyCount;
            comments.decrementReplyCount(currentId);
            int after = Math.max(before - 1, 0);

            boolean becameHidden = current.deletedAt != null && before > 0 && after == 0;
            if (!becameHidden || current.parentId == null) return;
            currentId = current.parentId;
        }
    }

    @Transactional
    public LikeResult like(String firebaseUid, long commentId, AnonProofService.AnonActionProof anonProof) {
        var comment = comments.findById(commentId);
        if (comment.isEmpty()) return LikeResult.commentNotFound();
        if (comment.get().deletedAt != null) return LikeResult.commentNotFound();
        if (comment.get().removedAt != null) return LikeResult.commentNotFound();
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
            if (post.get().communityId != null && communityBans.isBanned(actor.get().id, post.get().communityId)) {
                return LikeResult.communityBanned();
            }
            AccessStatus access = checkAccess(actor.get().id, post.get().communityId);
            if (access == AccessStatus.NOT_VERIFIED) return LikeResult.notVerified();
            if (access == AccessStatus.SPECIALIZATION_NOT_JOINED) return LikeResult.specializationNotJoined();
            actorPrincipalId = principals.createForUser(actor.get().id).id;
            actorUserId = actor.get().id;
        }
        if (post.get().visibility != null && !post.get().visibility.equalsIgnoreCase("public")
                && actorPrincipalId != post.get().authorPrincipalId) {
            return LikeResult.commentNotFound();
        }
        if (comment.get().visibility != null && !comment.get().visibility.equalsIgnoreCase("public")
                && actorPrincipalId != comment.get().authorPrincipalId) {
            return LikeResult.commentNotFound();
        }
        boolean created = comments.insertLikeIfAbsent(commentId, actorPrincipalId, actorUserId);
        if (created) {
            comments.incrementCommentLikes(commentId);
        }
        var view = comments.findViewById(commentId, actorPrincipalId, post.get().authorPrincipalId).orElseThrow();
        return LikeResult.ok(created, view.comment.likesCount, view.likedByCreator, view.viewerLiked);
    }

    @Transactional
    public UnlikeResult unlike(String firebaseUid, long commentId, AnonProofService.AnonActionProof anonProof) {
        var comment = comments.findById(commentId);
        if (comment.isEmpty()) return UnlikeResult.commentNotFound();
        if (comment.get().deletedAt != null) return UnlikeResult.commentNotFound();
        if (comment.get().removedAt != null) return UnlikeResult.commentNotFound();
        var post = posts.findById(comment.get().postId);
        if (post.isEmpty()) return UnlikeResult.commentNotFound();
        long actorPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            if (post.get().communityId == null) return UnlikeResult.invalidAnonProof();
            var verified = anonProofs.verifyActionScoped(anonProof, "comment_unlike", commentId, post.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return UnlikeResult.invalidAnonProof();
            actorPrincipalId = verified.actor().principalId();
        } else {
            if (firebaseUid == null) return UnlikeResult.userNotProvisioned();
            var actor = users.findByFirebaseUid(firebaseUid);
            if (actor.isEmpty() || actor.get().companyId == null) return UnlikeResult.userNotProvisioned();
            if (post.get().communityId != null && communityBans.isBanned(actor.get().id, post.get().communityId)) {
                return UnlikeResult.communityBanned();
            }
            AccessStatus access = checkAccess(actor.get().id, post.get().communityId);
            if (access == AccessStatus.NOT_VERIFIED) return UnlikeResult.notVerified();
            if (access == AccessStatus.SPECIALIZATION_NOT_JOINED) return UnlikeResult.specializationNotJoined();
            actorPrincipalId = principals.createForUser(actor.get().id).id;
        }
        if (post.get().visibility != null && !post.get().visibility.equalsIgnoreCase("public")
                && actorPrincipalId != post.get().authorPrincipalId) {
            return UnlikeResult.commentNotFound();
        }
        if (comment.get().visibility != null && !comment.get().visibility.equalsIgnoreCase("public")
                && actorPrincipalId != comment.get().authorPrincipalId) {
            return UnlikeResult.commentNotFound();
        }
        boolean deleted = comments.deleteLikeIfPresent(commentId, actorPrincipalId);
        if (deleted) {
            comments.decrementCommentLikes(commentId);
        }
        var view = comments.findViewById(commentId, actorPrincipalId, post.get().authorPrincipalId).orElseThrow();
        return UnlikeResult.ok(deleted, view.comment.likesCount, view.likedByCreator, view.viewerLiked);
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
        COMMUNITY_BANNED,
        NOT_VERIFIED,
        SPECIALIZATION_NOT_JOINED,
        INVALID_ANON_PROOF,
        ANON_MEDIA_NOT_ALLOWED,
        CONTENT_UNDER_REVIEW,
        MEDIA_NOT_FOUND
    }

    public record ListResult(Status status, List<CommentsRepository.CommentViewRow> comments, String nextCursor) {
        static ListResult ok(List<CommentsRepository.CommentViewRow> comments, String nextCursor) { return new ListResult(Status.OK, comments, nextCursor); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static ListResult postNotFound() { return new ListResult(Status.POST_NOT_FOUND, List.of(), null); }
        static ListResult communityBanned() { return new ListResult(Status.COMMUNITY_BANNED, List.of(), null); }
        static ListResult invalidAnonProof() { return new ListResult(Status.INVALID_ANON_PROOF, List.of(), null); }
    }

    public record CreateResult(Status status, CommentsRepository.CommentViewRow comment) {
        static CreateResult ok(CommentsRepository.CommentViewRow comment) { return new CreateResult(Status.OK, comment); }
        static CreateResult userNotProvisioned() { return new CreateResult(Status.USER_NOT_PROVISIONED, null); }
        static CreateResult postNotFound() { return new CreateResult(Status.POST_NOT_FOUND, null); }
        static CreateResult parentNotFound() { return new CreateResult(Status.COMMENT_NOT_FOUND, null); }
        static CreateResult invalidParent() { return new CreateResult(Status.INVALID_PARENT, null); }
        static CreateResult communityNotFound() { return new CreateResult(Status.COMMUNITY_NOT_FOUND, null); }
        static CreateResult communityBanned() { return new CreateResult(Status.COMMUNITY_BANNED, null); }
        static CreateResult notVerified() { return new CreateResult(Status.NOT_VERIFIED, null); }
        static CreateResult specializationNotJoined() { return new CreateResult(Status.SPECIALIZATION_NOT_JOINED, null); }
        static CreateResult invalidAnonProof() { return new CreateResult(Status.INVALID_ANON_PROOF, null); }
        static CreateResult anonMediaNotAllowed() { return new CreateResult(Status.ANON_MEDIA_NOT_ALLOWED, null); }
        static CreateResult contentUnderReview() { return new CreateResult(Status.CONTENT_UNDER_REVIEW, null); }
        static CreateResult mediaNotFound() { return new CreateResult(Status.MEDIA_NOT_FOUND, null); }
    }

    public record RepliesResult(Status status, List<CommentsRepository.CommentViewRow> comments, String nextCursor) {
        static RepliesResult ok(List<CommentsRepository.CommentViewRow> comments, String nextCursor) { return new RepliesResult(Status.OK, comments, nextCursor); }
        static RepliesResult userNotProvisioned() { return new RepliesResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static RepliesResult commentNotFound() { return new RepliesResult(Status.COMMENT_NOT_FOUND, List.of(), null); }
        static RepliesResult userNotFound() { return new RepliesResult(Status.USER_NOT_FOUND, List.of(), null); }
        static RepliesResult invalidAnonProof() { return new RepliesResult(Status.INVALID_ANON_PROOF, List.of(), null); }
    }

    public enum PublicListStatus { OK, POST_NOT_FOUND, POST_UNAVAILABLE }

    public record PublicListResult(PublicListStatus status, List<CommentsRepository.CommentViewRow> comments, String nextCursor) {
        static PublicListResult ok(List<CommentsRepository.CommentViewRow> comments, String nextCursor) {
            return new PublicListResult(PublicListStatus.OK, comments, nextCursor);
        }
        static PublicListResult postNotFound() { return new PublicListResult(PublicListStatus.POST_NOT_FOUND, List.of(), null); }
        static PublicListResult postUnavailable() { return new PublicListResult(PublicListStatus.POST_UNAVAILABLE, List.of(), null); }
    }

    public enum PublicRepliesStatus { OK, COMMENT_NOT_FOUND, POST_UNAVAILABLE }

    public record PublicRepliesResult(PublicRepliesStatus status, List<CommentsRepository.CommentViewRow> comments, String nextCursor) {
        static PublicRepliesResult ok(List<CommentsRepository.CommentViewRow> comments, String nextCursor) {
            return new PublicRepliesResult(PublicRepliesStatus.OK, comments, nextCursor);
        }
        static PublicRepliesResult commentNotFound() {
            return new PublicRepliesResult(PublicRepliesStatus.COMMENT_NOT_FOUND, List.of(), null);
        }
        static PublicRepliesResult postUnavailable() {
            return new PublicRepliesResult(PublicRepliesStatus.POST_UNAVAILABLE, List.of(), null);
        }
    }

    public record LikeResult(Status status, boolean created, int likesCount, boolean likedByCreator, boolean userLiked) {
        static LikeResult ok(boolean created, int likesCount, boolean likedByCreator, boolean userLiked) { return new LikeResult(Status.OK, created, likesCount, likedByCreator, userLiked); }
        static LikeResult userNotProvisioned() { return new LikeResult(Status.USER_NOT_PROVISIONED, false, 0, false, false); }
        static LikeResult commentNotFound() { return new LikeResult(Status.COMMENT_NOT_FOUND, false, 0, false, false); }
        static LikeResult communityBanned() { return new LikeResult(Status.COMMUNITY_BANNED, false, 0, false, false); }
        static LikeResult notVerified() { return new LikeResult(Status.NOT_VERIFIED, false, 0, false, false); }
        static LikeResult specializationNotJoined() { return new LikeResult(Status.SPECIALIZATION_NOT_JOINED, false, 0, false, false); }
        static LikeResult invalidAnonProof() { return new LikeResult(Status.INVALID_ANON_PROOF, false, 0, false, false); }
    }

    public enum EditStatus {
        OK,
        USER_NOT_PROVISIONED,
        COMMENT_NOT_FOUND,
        FORBIDDEN,
        COMMENT_DELETED,
        INVALID_ANON_PROOF,
        CONTENT_UNDER_REVIEW
    }

    public record EditResult(EditStatus status, CommentsRepository.CommentViewRow comment) {
        static EditResult ok(CommentsRepository.CommentViewRow comment) { return new EditResult(EditStatus.OK, comment); }
        static EditResult userNotProvisioned() { return new EditResult(EditStatus.USER_NOT_PROVISIONED, null); }
        static EditResult commentNotFound() { return new EditResult(EditStatus.COMMENT_NOT_FOUND, null); }
        static EditResult forbidden() { return new EditResult(EditStatus.FORBIDDEN, null); }
        static EditResult commentDeleted() { return new EditResult(EditStatus.COMMENT_DELETED, null); }
        static EditResult invalidAnonProof() { return new EditResult(EditStatus.INVALID_ANON_PROOF, null); }
        static EditResult contentUnderReview() { return new EditResult(EditStatus.CONTENT_UNDER_REVIEW, null); }
    }

    public enum DeleteStatus {
        OK,
        USER_NOT_PROVISIONED,
        COMMENT_NOT_FOUND,
        COMMUNITY_BANNED,
        FORBIDDEN,
        INVALID_ANON_PROOF
    }

    public record DeleteResult(DeleteStatus status, boolean deleted) {
        static DeleteResult ok(boolean deleted) { return new DeleteResult(DeleteStatus.OK, deleted); }
        static DeleteResult userNotProvisioned() { return new DeleteResult(DeleteStatus.USER_NOT_PROVISIONED, false); }
        static DeleteResult commentNotFound() { return new DeleteResult(DeleteStatus.COMMENT_NOT_FOUND, false); }
        static DeleteResult communityBanned() { return new DeleteResult(DeleteStatus.COMMUNITY_BANNED, false); }
        static DeleteResult forbidden() { return new DeleteResult(DeleteStatus.FORBIDDEN, false); }
        static DeleteResult invalidAnonProof() { return new DeleteResult(DeleteStatus.INVALID_ANON_PROOF, false); }
    }

    public enum UnlikeStatus {
        OK,
        USER_NOT_PROVISIONED,
        COMMENT_NOT_FOUND,
        COMMUNITY_BANNED,
        NOT_VERIFIED,
        SPECIALIZATION_NOT_JOINED,
        INVALID_ANON_PROOF
    }

    public record UnlikeResult(UnlikeStatus status, boolean deleted, int likesCount, boolean likedByCreator, boolean userLiked) {
        static UnlikeResult ok(boolean deleted, int likesCount, boolean likedByCreator, boolean userLiked) {
            return new UnlikeResult(UnlikeStatus.OK, deleted, likesCount, likedByCreator, userLiked);
        }
        static UnlikeResult userNotProvisioned() { return new UnlikeResult(UnlikeStatus.USER_NOT_PROVISIONED, false, 0, false, false); }
        static UnlikeResult commentNotFound() { return new UnlikeResult(UnlikeStatus.COMMENT_NOT_FOUND, false, 0, false, false); }
        static UnlikeResult communityBanned() { return new UnlikeResult(UnlikeStatus.COMMUNITY_BANNED, false, 0, false, false); }
        static UnlikeResult notVerified() { return new UnlikeResult(UnlikeStatus.NOT_VERIFIED, false, 0, false, false); }
        static UnlikeResult specializationNotJoined() { return new UnlikeResult(UnlikeStatus.SPECIALIZATION_NOT_JOINED, false, 0, false, false); }
        static UnlikeResult invalidAnonProof() { return new UnlikeResult(UnlikeStatus.INVALID_ANON_PROOF, false, 0, false, false); }
    }
}
