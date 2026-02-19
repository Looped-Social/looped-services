package com.looped.moderation;

import com.looped.comments.CommentsRepository;
import com.looped.discovery.HashtagParser;
import com.looped.discovery.HashtagPostsRepository;
import com.looped.discovery.HashtagsRepository;
import com.looped.media.MediaRepository;
import com.looped.posts.PostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ModerationQueueAdminService {
    private final ModerationQueueRepository queue;
    private final PostRepository posts;
    private final CommentsRepository comments;
    private final MediaRepository media;
    private final HashtagsRepository hashtags;
    private final HashtagPostsRepository hashtagPosts;
    private final ReportRepository reports;

    public ModerationQueueAdminService(ModerationQueueRepository queue,
                                       PostRepository posts,
                                       CommentsRepository comments,
                                       MediaRepository media,
                                       HashtagsRepository hashtags,
                                       HashtagPostsRepository hashtagPosts,
                                       ReportRepository reports) {
        this.queue = queue;
        this.posts = posts;
        this.comments = comments;
        this.media = media;
        this.hashtags = hashtags;
        this.hashtagPosts = hashtagPosts;
        this.reports = reports;
    }

    public ListResult list(String status, String targetType, OffsetDateTime cursorTs, Long cursorId, int limit) {
        var items = queue.list(status, targetType, cursorTs, cursorId, limit);
        String next = null;
        if (items.size() == limit) {
            var last = items.get(items.size() - 1);
            next = com.looped.shared.Pagination.encode(last.createdAt, last.id);
        }
        return ListResult.ok(items, next);
    }

    @Transactional
    public ResolveResult approve(long queueId, long adminId, String note) {
        var item = queue.findById(queueId);
        if (item.isEmpty()) return ResolveResult.notFound();
        if (!"open".equalsIgnoreCase(item.get().status)) return ResolveResult.alreadyReviewed(item.get().status);

        if ("post".equalsIgnoreCase(item.get().targetType)) {
            long postId = item.get().targetId;
            var post = posts.findByIdIncludingRemoved(postId);
            if (post.isEmpty() || post.get().removedAt != null) return ResolveResult.targetNotFound();
            boolean changed = posts.unquarantine(postId);
            if (changed) {
                indexHashtags(postId, post.get().companyId, post.get().content);
            }
            boolean updated = queue.review(queueId, "approved", adminId, note);
            return updated ? ResolveResult.ok("approved") : ResolveResult.alreadyReviewed("not_open");
        }

        if ("comment".equalsIgnoreCase(item.get().targetType)) {
            long commentId = item.get().targetId;
            var comment = comments.findById(commentId);
            if (comment.isEmpty() || comment.get().deletedAt != null || comment.get().removedAt != null) {
                return ResolveResult.targetNotFound();
            }
            boolean changed = comments.unquarantine(commentId);
            if (changed) {
                posts.incrementCommentsCount(comment.get().postId);
                if (comment.get().parentId != null) {
                    comments.incrementReplyCount(comment.get().parentId);
                }
            }
            boolean updated = queue.review(queueId, "approved", adminId, note);
            return updated ? ResolveResult.ok("approved") : ResolveResult.alreadyReviewed("not_open");
        }

        if ("media".equalsIgnoreCase(item.get().targetType)) {
            long mediaId = item.get().targetId;
            var m = media.findById(mediaId);
            if (m.isEmpty() || m.get().removedAt != null) return ResolveResult.targetNotFound();
            media.unquarantine(mediaId);
            boolean updated = queue.review(queueId, "approved", adminId, note);
            return updated ? ResolveResult.ok("approved") : ResolveResult.alreadyReviewed("not_open");
        }

        return ResolveResult.invalidTargetType();
    }

    @Transactional
    public ResolveResult remove(long queueId, long adminId, String reason, String note) {
        var item = queue.findById(queueId);
        if (item.isEmpty()) return ResolveResult.notFound();
        if (!"open".equalsIgnoreCase(item.get().status)) return ResolveResult.alreadyReviewed(item.get().status);

        String normalizedReason = reason == null || reason.isBlank() ? "admin_removed" : reason.trim();

        if ("post".equalsIgnoreCase(item.get().targetType)) {
            long postId = item.get().targetId;
            boolean removed = posts.remove(postId, adminId, normalizedReason);
            hashtagPosts.deleteByPostId(postId);
            if (removed) {
                reports.resolveOpenByTarget("post", postId, adminId, normalizedReason);
            }
            boolean updated = queue.review(queueId, "removed", adminId, note);
            if (!updated) return ResolveResult.alreadyReviewed("not_open");
            return removed ? ResolveResult.ok("removed") : ResolveResult.targetNotFound();
        }

        if ("comment".equalsIgnoreCase(item.get().targetType)) {
            long commentId = item.get().targetId;
            var comment = comments.findById(commentId);
            if (comment.isEmpty() || comment.get().deletedAt != null || comment.get().removedAt != null) {
                return ResolveResult.targetNotFound();
            }
            boolean wasPublic = comment.get().visibility == null || comment.get().visibility.equalsIgnoreCase("public");
            boolean removed = comments.removeByAdmin(commentId, adminId, normalizedReason);
            if (removed && wasPublic) {
                posts.decrementCommentsCount(comment.get().postId);
                if (comment.get().parentId != null) {
                    comments.decrementReplyCount(comment.get().parentId);
                }
            }
            if (removed) {
                reports.resolveOpenByTarget("comment", commentId, adminId, normalizedReason);
            }
            boolean updated = queue.review(queueId, "removed", adminId, note);
            if (!updated) return ResolveResult.alreadyReviewed("not_open");
            return removed ? ResolveResult.ok("removed") : ResolveResult.targetNotFound();
        }

        if ("media".equalsIgnoreCase(item.get().targetType)) {
            long mediaId = item.get().targetId;
            boolean removed = media.removeByAdmin(mediaId, adminId, normalizedReason);
            boolean updated = queue.review(queueId, "removed", adminId, note);
            if (!updated) return ResolveResult.alreadyReviewed("not_open");
            return removed ? ResolveResult.ok("removed") : ResolveResult.targetNotFound();
        }

        return ResolveResult.invalidTargetType();
    }

    private void indexHashtags(long postId, long companyId, String content) {
        var tags = HashtagParser.extract(content);
        if (tags.isEmpty()) return;
        hashtagPosts.deleteByPostId(postId);
        for (String tag : tags) {
            long hashtagId = hashtags.upsert(companyId, tag);
            hashtagPosts.attach(hashtagId, postId);
        }
    }

    public enum Status { OK, NOT_FOUND, TARGET_NOT_FOUND, INVALID_TARGET_TYPE, ALREADY_REVIEWED }

    public record ListResult(Status status, List<ModerationQueueRepository.ItemRow> items, String nextCursor) {
        static ListResult ok(List<ModerationQueueRepository.ItemRow> items, String nextCursor) {
            return new ListResult(Status.OK, items, nextCursor);
        }
    }

    public record ResolveResult(Status status, String finalStatus, String priorStatus) {
        static ResolveResult ok(String finalStatus) { return new ResolveResult(Status.OK, finalStatus, null); }
        static ResolveResult notFound() { return new ResolveResult(Status.NOT_FOUND, null, null); }
        static ResolveResult targetNotFound() { return new ResolveResult(Status.TARGET_NOT_FOUND, null, null); }
        static ResolveResult invalidTargetType() { return new ResolveResult(Status.INVALID_TARGET_TYPE, null, null); }
        static ResolveResult alreadyReviewed(String prior) { return new ResolveResult(Status.ALREADY_REVIEWED, null, prior); }
    }
}
