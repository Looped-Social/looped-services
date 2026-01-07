package com.looped.discovery;

import com.looped.shared.Pagination;
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
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = kind == null
                ? communities.search(query, cTs, cId, limit)
                : (specializationType == null
                    ? communities.searchByKind(kind, query, cTs, cId, limit)
                    : communities.searchByKindAndSpecializationType(kind, specializationType, query, cTs, cId, limit));
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return CommunitySearchResult.ok(rows, next);
    }

    public RecommendedCommunitiesResult recommendedCommunities(String firebaseUid, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty()) return RecommendedCommunitiesResult.userNotProvisioned();
        var rows = communities.recommended(actor.get().id, limit);
        return RecommendedCommunitiesResult.ok(rows);
    }

    public HashtagSearchResult searchHashtags(String firebaseUid, String query, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return HashtagSearchResult.userNotProvisioned();
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = hashtags.search(actor.get().companyId, query, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return HashtagSearchResult.ok(rows, next);
    }

    public HashtagPostsResult postsByHashtag(String firebaseUid, String name, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return HashtagPostsResult.userNotProvisioned();
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
        var rows = posts.findByHashtag(actor.get().companyId, normalized, cTs, cId, limit);
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

    public record RecommendedCommunitiesResult(RecommendedStatus status, List<CommunitiesRepository.RecommendedRow> items) {
        static RecommendedCommunitiesResult ok(List<CommunitiesRepository.RecommendedRow> items) { return new RecommendedCommunitiesResult(RecommendedStatus.OK, items); }
        static RecommendedCommunitiesResult userNotProvisioned() { return new RecommendedCommunitiesResult(RecommendedStatus.USER_NOT_PROVISIONED, List.of()); }
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
