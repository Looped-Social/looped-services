package com.looped.users;

import com.looped.settings.AppConfigService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class PublicProfilesService {
    private final UserRepository users;
    private final AppConfigService appConfig;

    public PublicProfilesService(UserRepository users, AppConfigService appConfig) {
        this.users = users;
        this.appConfig = appConfig;
    }

    public Result getByUsername(String rawUsername) {
        String normalized = normalizeUsername(rawUsername);
        if (normalized == null) return Result.notFound();

        var user = users.findByHandleIncludingDeleted(normalized);
        if (user.isEmpty()) return Result.notFound();
        if (user.get().deletedAt != null || user.get().disabledAt != null) return Result.unavailable();
        if (user.get().companyId == null) return Result.notFound();

        Long userId = user.get().id;
        if (userId == null) return Result.unavailable();

        Integer followersCount = user.get().showFollowerCount ? users.countFollowers(userId) : null;
        Integer followingCount = user.get().showFollowerCount ? users.countFollowing(userId) : null;
        String displayCommunityName = users.findDisplayCommunityForUser(userId).map(c -> c.name).orElse(null);
        String displaySpecializationName = users.findDisplaySpecializationForUser(userId).map(s -> s.name).orElse(null);

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", userId);
        payload.put("username", user.get().handle);
        payload.put("handle", user.get().handle);
        payload.put("display_name", user.get().displayName);
        payload.put("bio", user.get().bio);
        payload.put("profile_image_url", ProfileImageUrls.resolve(user.get().profileImageUrl, appConfig.defaultProfileImageUrl()));
        payload.put("display_community_name", displayCommunityName);
        payload.put("display_specialization_name", displaySpecializationName);
        payload.put("show_follower_count", user.get().showFollowerCount);
        payload.put("followers_count", followersCount);
        payload.put("following_count", followingCount);
        return Result.ok(payload);
    }

    static String normalizeUsername(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) return null;
        if (!normalized.matches("^[a-z0-9_]{3,30}$")) return null;
        return normalized;
    }

    public enum Status { OK, NOT_FOUND, UNAVAILABLE }

    public record Result(Status status, Map<String, Object> profile) {
        static Result ok(Map<String, Object> profile) { return new Result(Status.OK, profile); }
        static Result notFound() { return new Result(Status.NOT_FOUND, null); }
        static Result unavailable() { return new Result(Status.UNAVAILABLE, null); }
    }
}
