package com.looped.moderation;

import com.looped.comments.CommentsRepository;
import com.looped.discovery.HashtagPostsRepository;
import com.looped.posts.PostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuarantineService {
    private final PostRepository posts;
    private final CommentsRepository comments;
    private final ModerationQueueRepository queue;
    private final HashtagPostsRepository hashtagPosts;

    public QuarantineService(PostRepository posts, CommentsRepository comments, ModerationQueueRepository queue, HashtagPostsRepository hashtagPosts) {
        this.posts = posts;
        this.comments = comments;
        this.queue = queue;
        this.hashtagPosts = hashtagPosts;
    }

    @Transactional
    public void quarantinePost(long postId, String source, String reason) {
        boolean updated = posts.quarantine(postId, reason);
        if (updated) {
            hashtagPosts.deleteByPostId(postId);
        }
        queue.enqueueIfAbsent("post", postId, source == null ? "manual" : source, reason);
    }

    @Transactional
    public void quarantineComment(long commentId, String source, String reason) {
        var existing = comments.findById(commentId);
        boolean updated = comments.quarantine(commentId, reason);
        if (updated && existing.isPresent()) {
            posts.decrementCommentsCount(existing.get().postId);
            if (existing.get().parentId != null) {
                comments.decrementReplyCount(existing.get().parentId);
            }
        }
        queue.enqueueIfAbsent("comment", commentId, source == null ? "manual" : source, reason);
    }
}
