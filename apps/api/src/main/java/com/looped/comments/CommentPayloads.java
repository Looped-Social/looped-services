package com.looped.comments;

import java.util.HashMap;
import java.util.Map;

public final class CommentPayloads {
    private CommentPayloads() {}

    public static Map<String, Object> from(CommentsRepository.CommentViewRow row) {
        return from(row, null);
    }

    public static Map<String, Object> from(CommentsRepository.CommentViewRow row, String defaultProfileImageUrl) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", row.comment.id);
        out.put("post_id", row.comment.postId);
        out.put("content", row.comment.content);
        out.put("media_asset_id", row.comment.mediaAssetId);
        out.put("created_at", row.comment.createdAt);
        out.put("likes_count", row.comment.likesCount);
        out.put("reply_count", row.comment.replyCount);
        out.put("user_liked", row.viewerLiked);
        out.put("liked_by_creator", row.likedByCreator);
        out.put("is_deleted", row.comment.deletedAt != null || row.comment.removedAt != null);
        boolean isUnderReview = row.comment.visibility != null && row.comment.visibility.equalsIgnoreCase("quarantined");
        out.put("is_under_review", isUnderReview);
        out.put("isUnderReview", isUnderReview);
        out.put("author_principal_id", row.author.principalId);
        out.put("author_is_anonymous", row.author.isAnonymous);
        out.put("is_anonymous", row.author.isAnonymous);
        if (row.comment.parentId != null) out.put("parent_id", row.comment.parentId);

        Map<String, Object> author = new HashMap<>();
        Long authorId = row.author.userId != null ? row.author.userId : row.author.anonProfileId;
        author.put("id", authorId);
        author.put("principal_id", row.author.principalId);
        author.put("is_anonymous", row.author.isAnonymous);
        author.put("display_name", row.author.displayName);
        author.put("username", row.author.handle);
        author.put("handle", row.author.handle);
        author.put("company_id", row.author.companyId);
        author.put("profile_image_url", com.looped.users.ProfileImageUrls.resolve(row.author.profileImageUrl, defaultProfileImageUrl));
        out.put("author", author);

        return out;
    }
}
