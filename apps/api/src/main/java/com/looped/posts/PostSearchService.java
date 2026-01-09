package com.looped.posts;

import com.looped.principals.PrincipalRepository;
import com.looped.shared.RankPagination;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class PostSearchService {
    private final PostRepository posts;
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final PostStateService postState;

    public PostSearchService(PostRepository posts, UserRepository users, PrincipalRepository principals, PostStateService postState) {
        this.posts = posts;
        this.users = users;
        this.principals = principals;
        this.postState = postState;
    }

    public SearchResult search(String firebaseUid, String query, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return SearchResult.userNotProvisioned();
        long viewerUserId = actor.get().id;
        boolean hideAnonymousPosts = actor.get().hideAnonymousPosts;

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

        String prefixQuery = PostSearchQuery.toPrefixTsquery(query);
        List<PostRepository.ScoredPostRow> rows = posts.searchCompanyPosts(
                actor.get().companyId,
                query,
                prefixQuery,
                asOf,
                score,
                cTs,
                cId,
                limit,
                viewerUserId,
                hideAnonymousPosts
        );

        var principal = principals.createForUser(actor.get().id);
        postState.applyForPrincipal(principal.id, rows);

        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = RankPagination.encode(asOf, last.score, last.createdAt, last.id);
        }
        return SearchResult.ok(rows, next);
    }

    public enum Status { OK, USER_NOT_PROVISIONED }

    public record SearchResult(Status status, List<PostRepository.ScoredPostRow> posts, String nextCursor) {
        static SearchResult ok(List<PostRepository.ScoredPostRow> posts, String next) { return new SearchResult(Status.OK, posts, next); }
        static SearchResult userNotProvisioned() { return new SearchResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
    }
}
