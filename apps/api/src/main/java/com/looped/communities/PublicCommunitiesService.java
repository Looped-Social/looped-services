package com.looped.communities;

import com.looped.posts.PostRepository;
import com.looped.shared.Pagination;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PublicCommunitiesService {
    private static final Set<String> SHAREABLE_KINDS = Set.of("company", "school", "sector", "specialization");

    private final CommunitiesRepository communities;
    private final CommunityMemberCountService memberCounts;
    private final PostRepository posts;

    public PublicCommunitiesService(CommunitiesRepository communities,
                                    CommunityMemberCountService memberCounts,
                                    PostRepository posts) {
        this.communities = communities;
        this.memberCounts = memberCounts;
        this.posts = posts;
    }

    public Result getById(long communityId) {
        var row = communities.findById(communityId).orElse(null);
        if (row == null) return Result.notFound();
        if (!isPubliclyShareable(row)) return Result.unavailable();

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", row.id);
        payload.put("name", row.name);
        if (row.shortName != null && !row.shortName.isBlank()) {
            payload.put("short_name", row.shortName);
        }
        if (row.description != null && !row.description.isBlank()) {
            payload.put("description", row.description);
        }
        CommunityImageSlots.putPayload(payload, row.imageUrl, row.profileImageUrl, null);
        payload.put("member_count", memberCounts.memberCount(row.id, row.kind));
        if (row.kind != null && !row.kind.isBlank()) {
            payload.put("kind", row.kind);
        }
        if (row.specializationType != null && !row.specializationType.isBlank()) {
            payload.put("specialization_type", row.specializationType);
        }
        return Result.ok(payload);
    }

    public PostsResult postsById(long communityId, String cursor, int limit) {
        var row = communities.findById(communityId).orElse(null);
        if (row == null) return PostsResult.notFound();
        if (!isPubliclyShareable(row)) return PostsResult.unavailable();

        OffsetDateTime cursorTs = null;
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cursorTs = decoded.timestamp();
                cursorId = decoded.id();
            } catch (IllegalArgumentException ignored) {
            }
        }

        var rows = posts.findNew(communityId, cursorTs, cursorId, limit, -1L, -1L, false);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return PostsResult.ok(rows, next);
    }

    private boolean isPubliclyShareable(CommunitiesRepository.CommunityRow row) {
        if (row == null) return false;
        if (row.name == null || row.name.isBlank()) return false;
        if (row.kind == null || row.kind.isBlank()) return false;
        String normalizedKind = row.kind.trim().toLowerCase(Locale.ROOT);
        return SHAREABLE_KINDS.contains(normalizedKind);
    }

    public enum Status { OK, NOT_FOUND, UNAVAILABLE }

    public record Result(Status status, Map<String, Object> community) {
        static Result ok(Map<String, Object> community) { return new Result(Status.OK, community); }
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
}
