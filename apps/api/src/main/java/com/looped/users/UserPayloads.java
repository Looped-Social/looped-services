package com.looped.users;

import com.looped.comments.CommentsRepository;

import java.util.HashMap;
import java.util.Map;

public final class UserPayloads {
    private UserPayloads() {}

    public static Map<String, Object> fromProfile(UsersService.UserProfile profile) {
        return fromProfile(profile, true, false);
    }

    public static Map<String, Object> fromProfile(UsersService.UserProfile profile, boolean includeFollowerCounts) {
        return fromProfile(profile, includeFollowerCounts, false);
    }

    public static Map<String, Object> fromProfile(UsersService.UserProfile profile, boolean includeFollowerCounts, boolean includePrivatePreferences) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", profile.id());
        map.put("handle", profile.handle());
        map.put("username", profile.handle());
        map.put("first_name", profile.firstName());
        map.put("last_name", profile.lastName());
        map.put("date_of_birth", profile.dateOfBirth());
        map.put("display_name", profile.displayName());
        map.put("bio", profile.bio());
        map.put("is_anonymous", profile.isAnonymous());
        map.put("show_follower_count", profile.showFollowerCount());
        map.put("message_permission", profile.messagePermission());
        if (includePrivatePreferences) {
            map.put("hide_anonymous_posts", profile.hideAnonymousPosts());
        }
        map.put("company_id", profile.companyId());
        map.put("created_at", profile.createdAt());
        map.put("profile_image_url", profile.profileImageUrl());
        if (profile.verification() != null) {
            Map<String, Object> verification = new HashMap<>();
            verification.put("method", profile.verification().method());
            verification.put("verified", profile.verification().verified());
            verification.put("verified_at", profile.verification().verifiedAt());
            map.put("verification", verification);
        }
        if (profile.displayCommunity() != null) {
            Map<String, Object> display = new HashMap<>();
            display.put("id", profile.displayCommunity().id());
            display.put("name", profile.displayCommunity().name());
            if (profile.displayCommunity().shortName() != null && !profile.displayCommunity().shortName().isBlank()) {
                display.put("short_name", profile.displayCommunity().shortName());
            }
            display.put("kind", profile.displayCommunity().kind());
            if (profile.displayCommunity().specializationType() != null) {
                display.put("specialization_type", profile.displayCommunity().specializationType());
            }
            map.put("display_community", display);
        }
        if (profile.displaySpecialization() != null) {
            Map<String, Object> display = new HashMap<>();
            display.put("id", profile.displaySpecialization().id());
            display.put("name", profile.displaySpecialization().name());
            if (profile.displaySpecialization().shortName() != null && !profile.displaySpecialization().shortName().isBlank()) {
                display.put("short_name", profile.displaySpecialization().shortName());
            }
            display.put("kind", profile.displaySpecialization().kind());
            if (profile.displaySpecialization().specializationType() != null) {
                display.put("specialization_type", profile.displaySpecialization().specializationType());
            }
            map.put("display_specialization", display);
        }
        if (profile.stats() != null) {
            Map<String, Object> stats = new HashMap<>();
            if (includeFollowerCounts) {
                stats.put("follower_count", profile.stats().followerCount());
                stats.put("following_count", profile.stats().followingCount());
            }
            stats.put("posts_count", profile.stats().postsCount());
            stats.put("comments_count", profile.stats().commentsCount());
            map.put("stats", stats);
        }
        return map;
    }

    public static Map<String, Object> directory(UserRepository.UserRow row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.id);
        map.put("handle", row.handle);
        map.put("username", row.handle);
        map.put("display_name", row.displayName);
        map.put("bio", row.bio);
        map.put("company_id", row.companyId);
        map.put("profile_image_url", row.profileImageUrl);
        return map;
    }

    public static Map<String, Object> comment(CommentsRepository.CommentRow row) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", row.id);
        map.put("post_id", row.postId);
        map.put("content", row.content);
        map.put("created_at", row.createdAt);
        map.put("is_deleted", row.deletedAt != null);
        if (row.parentId != null) map.put("parent_id", row.parentId);
        return map;
    }
}
