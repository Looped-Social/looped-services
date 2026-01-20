package com.looped.posts;

import com.looped.communities.CommunitiesRepository;
import com.looped.principals.PrincipalRepository;
import com.looped.shared.Pagination;
import com.looped.shared.RankPagination;
import com.looped.users.UserCommunityBanRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class FeedService {
    private final PostRepository posts;
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final CommunitiesRepository communities;
    private final PostStateService postState;
    private final RepostsRepository reposts;
    private final UserCommunityBanRepository communityBans;

    public FeedService(PostRepository posts, UserRepository users, PrincipalRepository principals,
                       CommunitiesRepository communities, PostStateService postState, RepostsRepository reposts,
                       UserCommunityBanRepository communityBans) {
        this.posts = posts;
        this.users = users;
        this.principals = principals;
        this.communities = communities;
        this.postState = postState;
        this.reposts = reposts;
        this.communityBans = communityBans;
    }

    public FeedResult feed(String firebaseUid, String cursor, int limit, Long communityId, String mode) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) {
            return FeedResult.userNotProvisioned();
        }
        if (communityId != null && communities.findById(communityId).isEmpty()) {
            return FeedResult.communityNotFound();
        }
        Mode resolved = Mode.from(mode);
        var principal = principals.createForUser(u.get().id);
        long viewerUserId = u.get().id;
        long viewerPrincipalId = principal.id;
        if (communityId != null && communityBans.isBanned(viewerUserId, communityId)) {
            return FeedResult.communityBanned();
        }
        boolean hideAnonymousPosts = u.get().hideAnonymousPosts;
        FeedResult result = resolved == Mode.NEW
                ? feedNew(cursor, limit, communityId, viewerUserId, viewerPrincipalId, hideAnonymousPosts)
                : resolved == Mode.FOLLOWING
                ? feedFollowing(cursor, limit, communityId, viewerUserId, viewerPrincipalId, hideAnonymousPosts)
                : feedForYou(cursor, limit, communityId, viewerUserId, viewerPrincipalId, hideAnonymousPosts);
        postState.applyForPrincipal(principal.id, result.items());
        applyRepostBanners(principal.id, result.items());
        return result;
    }

    private FeedResult feedNew(String cursor, int limit, Long communityId, long viewerUserId, long viewerPrincipalId, boolean hideAnonymousPosts) {
        OffsetDateTime cTs = null;
        Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var c = Pagination.decode(cursor);
                cTs = c.timestamp();
                cId = c.id();
            } catch (IllegalArgumentException ignored) {
                // treat as no cursor
            }
        }
        var list = posts.findNew(communityId, cTs, cId, limit, viewerUserId, viewerPrincipalId, hideAnonymousPosts);
        String next = null;
        if (list.size() == limit) {
            var last = list.get(list.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return FeedResult.ok(list, next);
    }

    private FeedResult feedForYou(String cursor, int limit, Long communityId, long viewerUserId, long viewerPrincipalId, boolean hideAnonymousPosts) {
        RankPagination.Cursor rankedCursor = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                rankedCursor = RankPagination.decode(cursor);
            } catch (IllegalArgumentException ignored) {
                // treat as no cursor
            }
        }
        OffsetDateTime asOf = rankedCursor == null ? OffsetDateTime.now() : rankedCursor.asOf();
        OffsetDateTime since = asOf.minusDays(30);
        Long score = rankedCursor == null ? null : rankedCursor.score();
        OffsetDateTime cTs = rankedCursor == null ? null : rankedCursor.timestamp();
        Long cId = rankedCursor == null ? null : rankedCursor.id();
        var list = communityId == null
                ? posts.findPopular(asOf, since, score, cTs, cId, limit, viewerUserId, viewerPrincipalId, hideAnonymousPosts)
                : posts.findPopularByCommunity(communityId, asOf, since, score, cTs, cId, limit, viewerUserId, viewerPrincipalId, hideAnonymousPosts);
        String next = null;
        if (list.size() == limit) {
            var last = list.get(list.size() - 1);
            long lastScore = popularityScore(last, asOf);
            next = RankPagination.encode(asOf, lastScore, last.createdAt, last.id);
        }
        return FeedResult.ok(list, next);
    }

    private FeedResult feedFollowing(String cursor, int limit, Long communityId, long viewerUserId, long viewerPrincipalId, boolean hideAnonymousPosts) {
        OffsetDateTime cTs = null;
        Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var c = Pagination.decode(cursor);
                cTs = c.timestamp();
                cId = c.id();
            } catch (IllegalArgumentException ignored) {
                // treat as no cursor
            }
        }
        var list = posts.findFollowing(communityId, cTs, cId, limit, viewerUserId, viewerPrincipalId, hideAnonymousPosts);
        String next = null;
        if (list.size() == limit) {
            var last = list.get(list.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return FeedResult.ok(list, next);
    }

    public TrendingResult trending(String firebaseUid, int limit, Long communityId) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) {
            return TrendingResult.userNotProvisioned();
        }
        if (communityId != null) {
            var community = communities.findById(communityId);
            if (community.isEmpty()) return TrendingResult.communityNotFound();
        }
        if (communityId != null && communityBans.isBanned(u.get().id, communityId)) {
            return TrendingResult.communityBanned();
        }
        OffsetDateTime asOf = OffsetDateTime.now();
        OffsetDateTime since = asOf.minusDays(3);
        var principal = principals.createForUser(u.get().id);
        var list = posts.findTrendingWithMedia(asOf, since, communityId, limit, u.get().id, principal.id, u.get().hideAnonymousPosts);
        postState.applyForPrincipal(principal.id, list);
        return TrendingResult.ok(list);
    }

    private void applyRepostBanners(long viewerPrincipalId, List<? extends PostRepository.PostRow> items) {
        if (items == null || items.isEmpty()) return;
        List<Long> postIds = items.stream().map(p -> p.id).distinct().toList();
        for (PostRepository.PostRow post : items) {
            post.repostedByFollowedUsers = java.util.List.of();
            post.repostedByFollowedUsersCount = 0;
        }
        var rows = reposts.followedRepostsForPosts(viewerPrincipalId, postIds);
        if (rows == null || rows.isEmpty()) return;
        java.util.Map<Long, java.util.List<PostRepository.RepostBannerUser>> usersByPost = new java.util.HashMap<>();
        java.util.Map<Long, Integer> countsByPost = new java.util.HashMap<>();
        for (var row : rows) {
            countsByPost.put(row.postId(), row.totalCount());
            usersByPost.computeIfAbsent(row.postId(), ignored -> new java.util.ArrayList<>())
                    .add(new PostRepository.RepostBannerUser(row.userId(), row.username()));
        }
        for (PostRepository.PostRow post : items) {
            Integer total = countsByPost.get(post.id);
            if (total == null) continue;
            post.repostedByFollowedUsersCount = total;
            post.repostedByFollowedUsers = usersByPost.getOrDefault(post.id, java.util.List.of());
        }
    }

    private long popularityScore(PostRepository.PostRow row, OffsetDateTime asOf) {
        long base = (row.likesCount * 2L + row.commentsCount + row.shareCount) * 1000L;
        long ageHours = java.time.Duration.between(row.createdAt, asOf).toHours();
        return base - ageHours;
    }

    public enum Status { OK, USER_NOT_PROVISIONED, COMMUNITY_NOT_FOUND, COMMUNITY_BANNED }
    public enum Mode {
        FOR_YOU, NEW, FOLLOWING;

        static Mode from(String raw) {
            if (raw == null || raw.isBlank()) return FOR_YOU;
            String normalized = raw.trim().toLowerCase();
            if (normalized.equals("new") || normalized.equals("recent")) return NEW;
            if (normalized.equals("following")) return FOLLOWING;
            return FOR_YOU;
        }
    }
    public record FeedResult(Status status, List<PostRepository.PostRow> items, String nextCursor) {
        static FeedResult ok(List<PostRepository.PostRow> items, String next) { return new FeedResult(Status.OK, items, next); }
        static FeedResult userNotProvisioned() { return new FeedResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static FeedResult communityNotFound() { return new FeedResult(Status.COMMUNITY_NOT_FOUND, List.of(), null); }
        static FeedResult communityBanned() { return new FeedResult(Status.COMMUNITY_BANNED, List.of(), null); }
    }

    public record TrendingResult(Status status, List<PostRepository.TrendingRow> items) {
        static TrendingResult ok(List<PostRepository.TrendingRow> items) { return new TrendingResult(Status.OK, items); }
        static TrendingResult userNotProvisioned() { return new TrendingResult(Status.USER_NOT_PROVISIONED, List.of()); }
        static TrendingResult communityNotFound() { return new TrendingResult(Status.COMMUNITY_NOT_FOUND, List.of()); }
        static TrendingResult communityBanned() { return new TrendingResult(Status.COMMUNITY_BANNED, List.of()); }
    }
}
