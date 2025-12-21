package com.looped.posts;

import com.looped.communities.CommunitiesRepository;
import com.looped.shared.Pagination;
import com.looped.shared.RankPagination;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class FeedService {
    private final PostRepository posts;
    private final UserRepository users;
    private final CommunitiesRepository communities;

    public FeedService(PostRepository posts, UserRepository users, CommunitiesRepository communities) {
        this.posts = posts;
        this.users = users;
        this.communities = communities;
    }

    public FeedResult feed(String firebaseUid, String cursor, int limit, Long communityId) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) {
            return FeedResult.userNotProvisioned();
        }
        if (communityId != null) {
            var community = communities.findById(communityId);
            if (community.isEmpty()) return FeedResult.communityNotFound();
            OffsetDateTime cTs = null; Long cId = null;
            if (cursor != null && !cursor.isBlank()) {
                try {
                    var c = Pagination.decode(cursor);
                    cTs = c.timestamp();
                    cId = c.id();
                } catch (IllegalArgumentException ignored) {
                    // treat as no cursor
                }
            }
            var list = posts.findFeedByCommunity(communityId, cTs, cId, limit);
            String next = null;
            if (list.size() == limit) {
                var last = list.get(list.size() - 1);
                next = Pagination.encode(last.createdAt, last.id);
            }
            return FeedResult.ok(list, next);
        }

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
        var list = posts.findPopular(asOf, since, score, cTs, cId, limit);
        String next = null;
        if (list.size() == limit) {
            var last = list.get(list.size() - 1);
            long lastScore = popularityScore(last, asOf);
            next = RankPagination.encode(asOf, lastScore, last.createdAt, last.id);
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
        OffsetDateTime asOf = OffsetDateTime.now();
        OffsetDateTime since = asOf.minusDays(3);
        var list = posts.findTrendingWithMedia(asOf, since, communityId, limit);
        return TrendingResult.ok(list);
    }

    private long popularityScore(PostRepository.PostRow row, OffsetDateTime asOf) {
        long base = (row.likesCount * 2L + row.commentsCount + row.shareCount) * 1000L;
        long ageHours = java.time.Duration.between(row.createdAt, asOf).toHours();
        return base - ageHours;
    }

    public enum Status { OK, USER_NOT_PROVISIONED, COMMUNITY_NOT_FOUND }
    public record FeedResult(Status status, List<PostRepository.PostRow> items, String nextCursor) {
        static FeedResult ok(List<PostRepository.PostRow> items, String next) { return new FeedResult(Status.OK, items, next); }
        static FeedResult userNotProvisioned() { return new FeedResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static FeedResult communityNotFound() { return new FeedResult(Status.COMMUNITY_NOT_FOUND, List.of(), null); }
    }

    public record TrendingResult(Status status, List<PostRepository.TrendingRow> items) {
        static TrendingResult ok(List<PostRepository.TrendingRow> items) { return new TrendingResult(Status.OK, items); }
        static TrendingResult userNotProvisioned() { return new TrendingResult(Status.USER_NOT_PROVISIONED, List.of()); }
        static TrendingResult communityNotFound() { return new TrendingResult(Status.COMMUNITY_NOT_FOUND, List.of()); }
    }
}
