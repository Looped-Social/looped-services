package com.looped.posts;

import java.util.HashMap;
import java.util.Map;

public final class PostPayloads {
    private PostPayloads() {}

    public static Map<String, Object> from(PostRepository.PostRow row) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", row.id);
        out.put("author_id", row.authorId);
        out.put("company_id", row.companyId);
        out.put("content", row.content);
        out.put("media_asset_id", row.mediaAssetId);
        out.put("likes_count", row.likesCount);
        out.put("comments_count", row.commentsCount);
        out.put("share_count", row.shareCount);
        out.put("created_at", row.createdAt);
        return out;
    }
}
