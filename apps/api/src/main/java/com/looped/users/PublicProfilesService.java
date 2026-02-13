package com.looped.users;

import com.looped.posts.PostRepository;
import com.looped.posts.RepostsRepository;
import com.looped.principals.PrincipalRepository;
import com.looped.settings.AppConfigService;
import com.looped.shared.Pagination;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class PublicProfilesService {
    private final UserRepository users;
    private final PostRepository posts;
    private final RepostsRepository reposts;
    private final PrincipalRepository principals;
    private final AppConfigService appConfig;

    public PublicProfilesService(UserRepository users,
                                 PostRepository posts,
                                 RepostsRepository reposts,
                                 PrincipalRepository principals,
                                 AppConfigService appConfig) {
        this.users = users;
        this.posts = posts;
        this.reposts = reposts;
        this.principals = principals;
        this.appConfig = appConfig;
    }

    public Result getByUsername(String rawUsername) {
        var target = resolvePublicTarget(rawUsername);
        if (target.status() == Status.NOT_FOUND) return Result.notFound();
        if (target.status() == Status.UNAVAILABLE) return Result.unavailable();

        var user = target.user();
        Long userId = user.id;
        if (userId == null) return Result.unavailable();

        Integer followersCount = user.showFollowerCount ? users.countFollowers(userId) : null;
        Integer followingCount = user.showFollowerCount ? users.countFollowing(userId) : null;
        String displayCommunityName = users.findDisplayCommunityForUser(userId).map(c -> c.name).orElse(null);
        String displaySpecializationName = users.findDisplaySpecializationForUser(userId).map(s -> s.name).orElse(null);

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", userId);
        payload.put("username", user.handle);
        payload.put("handle", user.handle);
        payload.put("display_name", user.displayName);
        payload.put("bio", user.bio);
        payload.put("profile_image_url", ProfileImageUrls.resolve(user.profileImageUrl, appConfig.defaultProfileImageUrl()));
        payload.put("display_community_name", displayCommunityName);
        payload.put("display_specialization_name", displaySpecializationName);
        payload.put("show_follower_count", user.showFollowerCount);
        payload.put("followers_count", followersCount);
        payload.put("following_count", followingCount);
        return Result.ok(payload);
    }

    public PostsResult postsByUsername(String rawUsername, String cursor, int limit) {
        var target = resolvePublicTarget(rawUsername);
        if (target.status() == Status.NOT_FOUND) return PostsResult.notFound();
        if (target.status() == Status.UNAVAILABLE) return PostsResult.unavailable();
        var user = target.user();
        if (user.id == null) return PostsResult.unavailable();

        OffsetDateTime cursorTs = null;
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cursorTs = decoded.timestamp();
                cursorId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        var rows = posts.findByAuthor(user.id, cursorTs, cursorId, limit, -1L, false);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return PostsResult.ok(rows, next);
    }

    public RepostsResult repostsByUsername(String rawUsername, String cursor, int limit) {
        var target = resolvePublicTarget(rawUsername);
        if (target.status() == Status.NOT_FOUND) return RepostsResult.notFound();
        if (target.status() == Status.UNAVAILABLE) return RepostsResult.unavailable();
        var user = target.user();
        if (user.id == null) return RepostsResult.unavailable();

        var principal = principals.findByUserId(user.id);
        if (principal.isEmpty()) return RepostsResult.ok(List.of(), null);

        OffsetDateTime cursorTs = null;
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cursorTs = decoded.timestamp();
                cursorId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        var rows = reposts.repostedPosts(principal.get().id, cursorTs, cursorId, limit, -1L, false);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.repostedAt(), last.repostId());
        }
        return RepostsResult.ok(rows, next);
    }

    private LookupResult resolvePublicTarget(String rawUsername) {
        String normalized = normalizeUsername(rawUsername);
        if (normalized == null) return new LookupResult(Status.NOT_FOUND, null);

        Optional<UserRepository.UserRow> user = users.findByHandleIncludingDeleted(normalized);
        if (user.isEmpty()) return new LookupResult(Status.NOT_FOUND, null);
        if (user.get().deletedAt != null || user.get().disabledAt != null) {
            return new LookupResult(Status.UNAVAILABLE, null);
        }
        if (user.get().companyId == null) return new LookupResult(Status.NOT_FOUND, null);
        return new LookupResult(Status.OK, user.get());
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

    private record LookupResult(Status status, UserRepository.UserRow user) {}

    public record Result(Status status, Map<String, Object> profile) {
        static Result ok(Map<String, Object> profile) { return new Result(Status.OK, profile); }
        static Result notFound() { return new Result(Status.NOT_FOUND, null); }
        static Result unavailable() { return new Result(Status.UNAVAILABLE, null); }
    }

    public record PostsResult(Status status, List<PostRepository.PostRow> posts, String nextCursor) {
        static PostsResult ok(List<PostRepository.PostRow> posts, String nextCursor) {
            return new PostsResult(Status.OK, posts, nextCursor);
        }
        static PostsResult notFound() {
            return new PostsResult(Status.NOT_FOUND, List.of(), null);
        }
        static PostsResult unavailable() {
            return new PostsResult(Status.UNAVAILABLE, List.of(), null);
        }
    }

    public record RepostsResult(Status status, List<RepostsRepository.RepostedPostRow> reposts, String nextCursor) {
        static RepostsResult ok(List<RepostsRepository.RepostedPostRow> reposts, String nextCursor) {
            return new RepostsResult(Status.OK, reposts, nextCursor);
        }
        static RepostsResult notFound() {
            return new RepostsResult(Status.NOT_FOUND, List.of(), null);
        }
        static RepostsResult unavailable() {
            return new RepostsResult(Status.UNAVAILABLE, List.of(), null);
        }
    }
}
