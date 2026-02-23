package com.looped.discovery;

import com.looped.shared.Pagination;
import com.looped.shared.RankPagination;
import com.looped.communities.CommunitiesRepository;
import com.looped.posts.PostRepository;
import com.looped.posts.PostStateService;
import com.looped.principals.PrincipalRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class DiscoveryService {
    private final CommunitiesRepository communities;
    private final HashtagsRepository hashtags;
    private final PostRepository posts;
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final PostStateService postState;

    public DiscoveryService(CommunitiesRepository communities, HashtagsRepository hashtags, PostRepository posts,
                            UserRepository users, PrincipalRepository principals, PostStateService postState) {
        this.communities = communities;
        this.hashtags = hashtags;
        this.posts = posts;
        this.users = users;
        this.principals = principals;
        this.postState = postState;
    }

    public CommunitySearchResult searchCommunities(String firebaseUid, String query, String kind, String specializationType,
                                                   String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty()) return CommunitySearchResult.userNotProvisioned();

        boolean hasCursor = cursor != null && !cursor.isBlank();
        RankPagination.Cursor rankedCursor = null;
        if (hasCursor) {
            try {
                rankedCursor = RankPagination.decode(cursor);
            } catch (IllegalArgumentException ignored) {}
        }
        OffsetDateTime asOf = rankedCursor == null ? OffsetDateTime.now() : rankedCursor.asOf();
        Long score = rankedCursor == null ? null : rankedCursor.score();
        OffsetDateTime cTs = rankedCursor == null ? null : rankedCursor.timestamp();
        Long cId = rankedCursor == null ? null : rankedCursor.id();

        String prefixQuery = DiscoverySearchQuery.toPrefixTsquery(query);
        var scored = kind == null
                ? communities.searchRanked(query, prefixQuery, asOf, score, cTs, cId, limit)
                : (specializationType == null
                    ? communities.searchRankedByKind(kind, query, prefixQuery, asOf, score, cTs, cId, limit)
                    : communities.searchRankedByKindAndSpecializationType(kind, specializationType, query, prefixQuery, asOf, score, cTs, cId, limit));
        var rows = scored.stream().map(r -> r.community).toList();
        if (!hasCursor && rows.isEmpty()) {
            rows = communities.searchByLikePopularity(query, kind, specializationType, limit);
        }
        String next = null;
        if (scored.size() == limit) {
            var last = scored.get(scored.size() - 1);
            next = RankPagination.encode(asOf, last.score, last.community.createdAt, last.community.id);
        }
        return CommunitySearchResult.ok(rows, next);
    }

    public CommunitySearchResult browseSpecializations(String firebaseUid,
                                                       String specializationType,
                                                       String cursor,
                                                       int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty()) return CommunitySearchResult.userNotProvisioned();

        RankPagination.Cursor rankedCursor = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                rankedCursor = RankPagination.decode(cursor);
            } catch (IllegalArgumentException ignored) {}
        }
        OffsetDateTime asOf = rankedCursor == null ? OffsetDateTime.now() : rankedCursor.asOf();
        Long memberCount = rankedCursor == null ? null : rankedCursor.score();
        OffsetDateTime cTs = rankedCursor == null ? null : rankedCursor.timestamp();
        Long cId = rankedCursor == null ? null : rankedCursor.id();

        var rows = communities.browseSpecializationsByMemberCount(specializationType, asOf, memberCount, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = RankPagination.encode(asOf, last.memberCount, last.createdAt, last.id);
        }
        return CommunitySearchResult.ok(rows, next);
    }

    public RecommendedCommunitiesResult recommendedCommunities(String firebaseUid, String kind,
                                                               String specializationType, int limit) {
        return recommendedCommunities(firebaseUid, kind, specializationType, null, limit);
    }

    public RecommendedCommunitiesResult recommendedCommunities(String firebaseUid, String kind,
                                                               String specializationType, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        Long userId = actor.map(u -> u.id).orElse(null);

        RankPagination.Cursor rankedCursor = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                rankedCursor = RankPagination.decode(cursor);
            } catch (IllegalArgumentException ignored) {}
        }
        OffsetDateTime asOf = rankedCursor == null ? OffsetDateTime.now() : rankedCursor.asOf();
        Long score = rankedCursor == null ? null : rankedCursor.score();
        OffsetDateTime cTs = rankedCursor == null ? null : rankedCursor.timestamp();
        Long cId = rankedCursor == null ? null : rankedCursor.id();

        var rows = communities.recommended(userId, kind, specializationType, asOf, score, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = RankPagination.encode(asOf, last.score, last.createdAt, last.id);
        }
        return RecommendedCommunitiesResult.ok(rows, next);
    }

    public HashtagSearchResult searchHashtags(String firebaseUid, String query, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return HashtagSearchResult.userNotProvisioned();

        RankPagination.Cursor rankedCursor = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                rankedCursor = RankPagination.decode(cursor);
            } catch (IllegalArgumentException ignored) {}
        }
        OffsetDateTime asOf = rankedCursor == null ? OffsetDateTime.now() : rankedCursor.asOf();
        Long score = rankedCursor == null ? null : rankedCursor.score();
        OffsetDateTime cTs = rankedCursor == null ? null : rankedCursor.timestamp();
        Long cId = rankedCursor == null ? null : rankedCursor.id();

        String prefixQuery = DiscoverySearchQuery.toPrefixTsquery(query);
        var scored = hashtags.searchRanked(actor.get().companyId, query, prefixQuery, asOf, score, cTs, cId, limit);
        var rows = scored.stream().map(r -> r.hashtag).toList();
        String next = null;
        if (scored.size() == limit) {
            var last = scored.get(scored.size() - 1);
            next = RankPagination.encode(asOf, last.score, last.hashtag.createdAt, last.hashtag.id);
        }
        return HashtagSearchResult.ok(rows, next);
    }

    public HashtagPostsResult postsByHashtag(String firebaseUid, String name, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return HashtagPostsResult.userNotProvisioned();
        long viewerUserId = actor.get().id;
        boolean hideAnonymousPosts = actor.get().hideAnonymousPosts;
        String normalized = HashtagParser.normalize(name);
        if (normalized == null) return HashtagPostsResult.invalidQuery();
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = posts.findByHashtag(actor.get().companyId, normalized, cTs, cId, limit, viewerUserId, hideAnonymousPosts);
        var principal = principals.createForUser(actor.get().id);
        postState.applyForPrincipal(principal.id, rows);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return HashtagPostsResult.ok(rows, next);
    }

    public enum Status { OK, USER_NOT_PROVISIONED }
    public enum RecommendedStatus { OK, USER_NOT_PROVISIONED }
    public enum HashtagPostsStatus { OK, USER_NOT_PROVISIONED, INVALID_QUERY }

    public record CommunitySearchResult(Status status, List<CommunitiesRepository.CommunityRow> items, String nextCursor) {
        static CommunitySearchResult ok(List<CommunitiesRepository.CommunityRow> items, String next) { return new CommunitySearchResult(Status.OK, items, next); }
        static CommunitySearchResult userNotProvisioned() { return new CommunitySearchResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
    }

    public record RecommendedCommunitiesResult(RecommendedStatus status, List<CommunitiesRepository.RecommendedRow> items, String nextCursor) {
        static RecommendedCommunitiesResult ok(List<CommunitiesRepository.RecommendedRow> items, String nextCursor) {
            return new RecommendedCommunitiesResult(RecommendedStatus.OK, items, nextCursor);
        }
        static RecommendedCommunitiesResult userNotProvisioned() {
            return new RecommendedCommunitiesResult(RecommendedStatus.USER_NOT_PROVISIONED, List.of(), null);
        }
    }

    public record HashtagSearchResult(Status status, List<HashtagsRepository.HashtagRow> items, String nextCursor) {
        static HashtagSearchResult ok(List<HashtagsRepository.HashtagRow> items, String next) { return new HashtagSearchResult(Status.OK, items, next); }
        static HashtagSearchResult userNotProvisioned() { return new HashtagSearchResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
    }

    public record HashtagPostsResult(HashtagPostsStatus status, List<PostRepository.PostRow> posts, String nextCursor) {
        static HashtagPostsResult ok(List<PostRepository.PostRow> posts, String next) { return new HashtagPostsResult(HashtagPostsStatus.OK, posts, next); }
        static HashtagPostsResult userNotProvisioned() { return new HashtagPostsResult(HashtagPostsStatus.USER_NOT_PROVISIONED, List.of(), null); }
        static HashtagPostsResult invalidQuery() { return new HashtagPostsResult(HashtagPostsStatus.INVALID_QUERY, List.of(), null); }
    }
}
