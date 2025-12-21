package com.looped.posts;

import java.util.HashMap;
import java.util.Map;

public final class PostPayloads {
    private PostPayloads() {}

    public static Map<String, Object> from(PostRepository.PostRow row) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", row.id);
        out.put("author_id", row.authorId);
        out.put("author_principal_id", row.authorPrincipalId);
        out.put("author_handle", row.authorHandle);
        out.put("author_display_name", row.authorDisplayName);
        out.put("author_profile_image_url", row.authorProfileImageUrl);
        out.put("author_is_anonymous", row.authorIsAnonymous);
        out.put("anon_profile_id", row.anonProfileId);
        out.put("company_id", row.companyId);
        out.put("community_id", row.communityId);
        out.put("content", row.content);
        out.put("media_asset_id", row.mediaAssetId);
        out.put("likes_count", row.likesCount);
        out.put("comments_count", row.commentsCount);
        out.put("share_count", row.shareCount);
        out.put("created_at", row.createdAt);
        return out;
    }

    public static Map<String, Object> fromSaved(PostRepository.PostRow row, boolean isSaved) {
        Map<String, Object> out = from(row);
        out.put("is_saved", isSaved);
        return out;
    }
}
