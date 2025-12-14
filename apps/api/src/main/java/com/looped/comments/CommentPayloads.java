package com.looped.comments;

import java.util.HashMap;
import java.util.Map;

public final class CommentPayloads {
    private CommentPayloads() {}

    public static Map<String, Object> from(CommentsRepository.CommentViewRow row) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", row.comment.id);
        out.put("post_id", row.comment.postId);
        out.put("content", row.comment.content);
        out.put("created_at", row.comment.createdAt);
        out.put("likes_count", row.comment.likesCount);
        out.put("user_liked", row.viewerLiked);
        out.put("liked_by_creator", row.likedByCreator);
        out.put("is_anonymous", row.author.isAnonymous);
        if (row.comment.parentId != null) out.put("parent_id", row.comment.parentId);

        Map<String, Object> author = new HashMap<>();
        author.put("id", row.author.id);
        author.put("display_name", row.author.displayName);
        author.put("username", row.author.handle);
        author.put("handle", row.author.handle);
        author.put("company_id", row.author.companyId);
        author.put("profile_image_url", row.author.profileImageUrl);
        out.put("author", author);

        return out;
    }
}
