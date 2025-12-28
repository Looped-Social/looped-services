package com.looped.anon;

import com.looped.posts.PostRepository;
import com.looped.principals.PrincipalProfilesRepository;
import com.looped.principals.PrincipalRepository;
import com.looped.principals.PrincipalStatsRepository;
import com.looped.shared.Pagination;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class AnonProfilesService {
    private final UserRepository users;
    private final AnonymousProfilesRepository profiles;
    private final PrincipalRepository principals;
    private final PrincipalStatsRepository stats;
    private final PostRepository posts;
    private final PrincipalProfilesRepository follows;

    public AnonProfilesService(UserRepository users,
                               AnonymousProfilesRepository profiles,
                               PrincipalRepository principals,
                               PrincipalStatsRepository stats,
                               PostRepository posts,
                               PrincipalProfilesRepository follows) {
        this.users = users;
        this.profiles = profiles;
        this.principals = principals;
        this.stats = stats;
        this.posts = posts;
        this.follows = follows;
    }

    public ProfileResult profile(String firebaseUid, long anonProfileId) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return ProfileResult.userNotProvisioned();
        var profile = profiles.findById(anonProfileId);
        if (profile.isEmpty()) return ProfileResult.notFound();
        if (profile.get().companyId != null && !actor.get().companyId.equals(profile.get().companyId)) {
            return ProfileResult.forbidden();
        }
        var principal = principals.createForAnon(anonProfileId);
        var statsBlock = new ProfileStats(
                stats.countFollowers(principal.id),
                stats.countFollowing(principal.id),
                stats.countPosts(principal.id)
        );
        return ProfileResult.ok(new AnonProfile(
                profile.get().id,
                profile.get().handle,
                profile.get().companyId,
                profile.get().createdAt,
                statsBlock
        ));
    }

    public PostsResult posts(String firebaseUid, long anonProfileId, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return PostsResult.userNotProvisioned();
        var profile = profiles.findById(anonProfileId);
        if (profile.isEmpty()) return PostsResult.notFound();
        if (profile.get().companyId != null && !actor.get().companyId.equals(profile.get().companyId)) {
            return PostsResult.forbidden();
        }
        var principal = principals.createForAnon(anonProfileId);

        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        var rows = posts.findByAuthorPrincipal(principal.id, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return PostsResult.ok(rows, next);
    }

    public FollowsResult followers(String firebaseUid, long anonProfileId, String cursor, int limit) {
        return followList(firebaseUid, anonProfileId, cursor, limit, true);
    }

    public FollowsResult following(String firebaseUid, long anonProfileId, String cursor, int limit) {
        return followList(firebaseUid, anonProfileId, cursor, limit, false);
    }

    private FollowsResult followList(String firebaseUid, long anonProfileId, String cursor, int limit, boolean followersList) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return FollowsResult.userNotProvisioned();
        var profile = profiles.findById(anonProfileId);
        if (profile.isEmpty()) return FollowsResult.notFound();
        if (profile.get().companyId != null && !actor.get().companyId.equals(profile.get().companyId)) {
            return FollowsResult.forbidden();
        }
        var principal = principals.createForAnon(anonProfileId);

        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        List<PrincipalProfilesRepository.PrincipalProfileRow> rows;
        if (followersList) {
            rows = follows.followers(principal.id, cTs, cId, limit);
        } else {
            rows = follows.following(principal.id, cTs, cId, limit);
        }
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.followCreatedAt, last.principalId);
        }
        return FollowsResult.ok(rows, next);
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN }

    public record ProfileResult(Status status, AnonProfile profile) {
        static ProfileResult ok(AnonProfile profile) { return new ProfileResult(Status.OK, profile); }
        static ProfileResult userNotProvisioned() { return new ProfileResult(Status.USER_NOT_PROVISIONED, null); }
        static ProfileResult notFound() { return new ProfileResult(Status.NOT_FOUND, null); }
        static ProfileResult forbidden() { return new ProfileResult(Status.FORBIDDEN, null); }
    }

    public record PostsResult(Status status, List<PostRepository.PostRow> posts, String nextCursor) {
        static PostsResult ok(List<PostRepository.PostRow> posts, String next) { return new PostsResult(Status.OK, posts, next); }
        static PostsResult userNotProvisioned() { return new PostsResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static PostsResult notFound() { return new PostsResult(Status.NOT_FOUND, List.of(), null); }
        static PostsResult forbidden() { return new PostsResult(Status.FORBIDDEN, List.of(), null); }
    }

    public record FollowsResult(Status status, List<PrincipalProfilesRepository.PrincipalProfileRow> principals, String nextCursor) {
        static FollowsResult ok(List<PrincipalProfilesRepository.PrincipalProfileRow> principals, String next) { return new FollowsResult(Status.OK, principals, next); }
        static FollowsResult userNotProvisioned() { return new FollowsResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static FollowsResult notFound() { return new FollowsResult(Status.NOT_FOUND, List.of(), null); }
        static FollowsResult forbidden() { return new FollowsResult(Status.FORBIDDEN, List.of(), null); }
    }

    public record AnonProfile(long id, String handle, Long companyId, OffsetDateTime createdAt, ProfileStats stats) {}

    public record ProfileStats(int followerCount, int followingCount, int postsCount) {}
}
