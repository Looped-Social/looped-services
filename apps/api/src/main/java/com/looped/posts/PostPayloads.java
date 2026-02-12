package com.looped.posts;

import java.util.HashMap;
import java.util.Map;

public final class PostPayloads {
    private PostPayloads() {}

    public static Map<String, Object> from(PostRepository.PostRow row) {
        return from(row, null);
    }

    public static Map<String, Object> from(PostRepository.PostRow row, String defaultProfileImageUrl) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", row.id);
        out.put("author_id", row.authorId);
        out.put("author_principal_id", row.authorPrincipalId);
        out.put("author_handle", row.authorHandle);
        out.put("author_display_name", row.authorDisplayName);
        if (row.authorIsAnonymous) {
            out.put("author_first_name", "Anonymous");
            out.put("author_last_name", null);
        } else {
            out.put("author_first_name", row.authorFirstName);
            out.put("author_last_name", row.authorLastName);
        }
        out.put("author_profile_image_url", com.looped.users.ProfileImageUrls.resolve(row.authorProfileImageUrl, defaultProfileImageUrl));
        out.put("author_is_anonymous", row.authorIsAnonymous);
        out.put("is_anonymous", row.authorIsAnonymous);
        if (!row.authorIsAnonymous && row.authorDisplayCommunityId != null) {
            Map<String, Object> display = new HashMap<>();
            display.put("id", row.authorDisplayCommunityId);
            display.put("name", row.authorDisplayCommunityName);
            if (row.authorDisplayCommunityShortName != null && !row.authorDisplayCommunityShortName.isBlank()) {
                display.put("short_name", row.authorDisplayCommunityShortName);
            }
            display.put("kind", row.authorDisplayCommunityKind);
            if (row.authorDisplayCommunitySpecializationType != null) {
                display.put("specialization_type", row.authorDisplayCommunitySpecializationType);
            }
            out.put("author_display_community", display);
        }
        if ((row.isAnon || !row.authorIsAnonymous) && row.authorDisplaySpecializationId != null) {
            Map<String, Object> display = new HashMap<>();
            display.put("id", row.authorDisplaySpecializationId);
            display.put("name", row.authorDisplaySpecializationName);
            if (row.authorDisplaySpecializationShortName != null && !row.authorDisplaySpecializationShortName.isBlank()) {
                display.put("short_name", row.authorDisplaySpecializationShortName);
            }
            display.put("kind", row.authorDisplaySpecializationKind);
            if (row.authorDisplaySpecializationType != null) {
                display.put("specialization_type", row.authorDisplaySpecializationType);
            }
            out.put("author_display_specialization", display);
        }
        out.put("anon_profile_id", row.anonProfileId);
        out.put("company_id", row.companyId);
        out.put("community_id", row.communityId);
        out.put("community_name", row.communityName);
        if (row.communityShortName != null && !row.communityShortName.isBlank()) {
            out.put("community_short_name", row.communityShortName);
        }
        out.put("community_kind", row.communityKind);
        out.put("content", row.content);
        out.put("media_asset_id", row.mediaAssetId);
        java.util.List<Long> mediaIds = row.mediaAssetIds != null ? row.mediaAssetIds
                : (row.mediaAssetId == null ? null : java.util.List.of(row.mediaAssetId));
        out.put("media_asset_ids", mediaIds);
        out.put("mediaAssetIds", mediaIds);
        out.put("likes_count", row.likesCount);
        out.put("comments_count", row.commentsCount);
        out.put("share_count", row.shareCount);
        boolean isUnderReview = row.visibility != null && row.visibility.equalsIgnoreCase("quarantined");
        out.put("is_under_review", isUnderReview);
        out.put("isUnderReview", isUnderReview);
        out.put("user_liked", row.userLiked);
        out.put("is_saved", row.isSaved);
        out.put("viewer_has_reposted", row.viewerHasReposted);
        out.put("viewerHasReposted", row.viewerHasReposted);
        if (row.repostedByFollowedUsersCount != null) {
            java.util.List<java.util.Map<String, Object>> users = row.repostedByFollowedUsers == null
                    ? java.util.List.of()
                    : row.repostedByFollowedUsers.stream()
                    .map(u -> java.util.Map.<String, Object>of(
                            "user_id", u.userId(),
                            "username", u.username()
                    ))
                    .toList();
            out.put("reposted_by_followed_users", users);
            out.put("reposted_by_followed_users_count", row.repostedByFollowedUsersCount);
            out.put("repostedByFollowedUsers", users);
            out.put("repostedByFollowedUsersCount", row.repostedByFollowedUsersCount);
        }
        out.put("created_at", row.createdAt);
        return out;
    }

    public static void putViewerCapabilities(Map<String, Object> payload, Map<String, Object> viewerCapabilities) {
        if (payload == null || viewerCapabilities == null || viewerCapabilities.isEmpty()) return;
        payload.put("viewerCapabilities", viewerCapabilities);
        payload.put("viewer_capabilities", viewerCapabilities);
    }

    public static Map<String, Object> search(PostRepository.PostRow row) {
        return search(row, null);
    }

    public static Map<String, Object> search(PostRepository.PostRow row, String defaultProfileImageUrl) {
        Map<String, Object> out = from(row, defaultProfileImageUrl);
        out.put("title", titleFromContent(row.content));
        return out;
    }

    public static Map<String, Object> fromSaved(PostRepository.PostRow row, boolean isSaved) {
        return fromSaved(row, isSaved, null);
    }

    public static Map<String, Object> fromSaved(PostRepository.PostRow row, boolean isSaved, String defaultProfileImageUrl) {
        Map<String, Object> out = from(row, defaultProfileImageUrl);
        out.put("is_saved", isSaved);
        return out;
    }

    public static Map<String, Object> publicFrom(PostRepository.PostRow row, String defaultProfileImageUrl) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", row.id);
        out.put("author_handle", row.authorHandle);
        out.put("author_display_name", publicAuthorDisplayName(row));
        out.put("author_profile_image_url", com.looped.users.ProfileImageUrls.resolve(row.authorProfileImageUrl, defaultProfileImageUrl));
        out.put("author_is_anonymous", row.authorIsAnonymous);
        out.put("is_anonymous", row.authorIsAnonymous);
        if (!row.authorIsAnonymous && row.authorDisplayCommunityId != null) {
            Map<String, Object> display = new HashMap<>();
            display.put("id", row.authorDisplayCommunityId);
            display.put("name", row.authorDisplayCommunityName);
            if (row.authorDisplayCommunityShortName != null && !row.authorDisplayCommunityShortName.isBlank()) {
                display.put("short_name", row.authorDisplayCommunityShortName);
            }
            display.put("kind", row.authorDisplayCommunityKind);
            if (row.authorDisplayCommunitySpecializationType != null) {
                display.put("specialization_type", row.authorDisplayCommunitySpecializationType);
            }
            out.put("author_display_community", display);
        }
        if ((row.isAnon || !row.authorIsAnonymous) && row.authorDisplaySpecializationId != null) {
            Map<String, Object> display = new HashMap<>();
            display.put("id", row.authorDisplaySpecializationId);
            display.put("name", row.authorDisplaySpecializationName);
            if (row.authorDisplaySpecializationShortName != null && !row.authorDisplaySpecializationShortName.isBlank()) {
                display.put("short_name", row.authorDisplaySpecializationShortName);
            }
            display.put("kind", row.authorDisplaySpecializationKind);
            if (row.authorDisplaySpecializationType != null) {
                display.put("specialization_type", row.authorDisplaySpecializationType);
            }
            out.put("author_display_specialization", display);
        }
        out.put("community_id", row.communityId);
        out.put("community_name", row.communityName);
        if (row.communityShortName != null && !row.communityShortName.isBlank()) {
            out.put("community_short_name", row.communityShortName);
        }
        out.put("community_kind", row.communityKind);
        out.put("content", row.content);
        out.put("media_asset_id", row.mediaAssetId);
        java.util.List<Long> mediaIds = row.mediaAssetIds != null ? row.mediaAssetIds
                : (row.mediaAssetId == null ? null : java.util.List.of(row.mediaAssetId));
        out.put("media_asset_ids", mediaIds);
        out.put("mediaAssetIds", mediaIds);
        out.put("likes_count", row.likesCount);
        out.put("comments_count", row.commentsCount);
        out.put("share_count", row.shareCount);
        out.put("created_at", row.createdAt);
        return out;
    }

    public static Map<String, Object> trending(PostRepository.TrendingRow row) {
        return trending(row, null);
    }

    public static Map<String, Object> trending(PostRepository.TrendingRow row, String defaultProfileImageUrl) {
        Map<String, Object> out = from(row, defaultProfileImageUrl);
        out.put("community_name", row.communityName);
        if (row.communityShortName != null && !row.communityShortName.isBlank()) {
            out.put("community_short_name", row.communityShortName);
        }
        out.put("community_kind", row.communityKind);
        out.put("title", titleFromContent(row.content));
        return out;
    }

    private static String titleFromContent(String content) {
        if (content == null) return "";
        String normalized = content.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 80) return normalized;
        return normalized.substring(0, 77) + "...";
    }

    private static String publicAuthorDisplayName(PostRepository.PostRow row) {
        if (row.authorIsAnonymous) return "Anonymous";
        if (row.authorDisplayName != null && !row.authorDisplayName.isBlank()) return row.authorDisplayName;
        if (row.authorHandle != null && !row.authorHandle.isBlank()) return row.authorHandle;
        return "Unknown";
    }
}
