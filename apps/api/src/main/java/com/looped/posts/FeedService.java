package com.looped.posts;

import com.looped.shared.Pagination;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class FeedService {
    private final PostRepository posts;
    private final UserRepository users;

    public FeedService(PostRepository posts, UserRepository users) {
        this.posts = posts;
        this.users = users;
    }

    public FeedResult feed(String firebaseUid, String cursor, int limit) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty() || u.get().companyId == null) {
            return FeedResult.userNotProvisioned();
        }
        long companyId = u.get().companyId;
        java.time.OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var c = Pagination.decode(cursor);
                cTs = c.timestamp();
                cId = c.id();
            } catch (IllegalArgumentException ignored) {
                // treat as no cursor
            }
        }

        var list = posts.findFeed(companyId, cTs, cId, limit);
        String next = null;
        if (list.size() == limit) {
            var last = list.get(list.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return FeedResult.ok(list, next);
    }

    public enum Status { OK, USER_NOT_PROVISIONED }
    public record FeedResult(Status status, List<PostRepository.PostRow> items, String nextCursor) {
        static FeedResult ok(List<PostRepository.PostRow> items, String next) { return new FeedResult(Status.OK, items, next); }
        static FeedResult userNotProvisioned() { return new FeedResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
    }
}
