package com.looped.communities;

import com.looped.shared.Pagination;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class CommunityFollowsService {
    private final UserRepository users;
    private final CommunityFollowsRepository follows;
    private final CommunitiesRepository communities;

    public CommunityFollowsService(UserRepository users, CommunityFollowsRepository follows, CommunitiesRepository communities) {
        this.users = users;
        this.follows = follows;
        this.communities = communities;
    }

    public ListResult followed(String firebaseUid, String cursor, int limit) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();

        var cursorParts = decodeCursor(cursor);
        var rows = follows.findFollowed(actor.get().id, cursorParts.timestamp, cursorParts.followId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.followedAt, last.followId);
        }
        return ListResult.ok(rows, next);
    }

    private Optional<UserRepository.UserRow> provisionedUser(String firebaseUid) {
        return users.findByFirebaseUid(firebaseUid);
    }

    private CursorParts decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new CursorParts(null, null);
        }
        try {
            var decoded = Pagination.decode(cursor);
            return new CursorParts(decoded.timestamp(), decoded.id());
        } catch (IllegalArgumentException ignored) {
            return new CursorParts(null, null);
        }
    }

    private record CursorParts(OffsetDateTime timestamp, Long followId) {}

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND }

    public record ListResult(Status status, List<CommunityFollowsRepository.FollowRow> follows, String nextCursor) {
        static ListResult ok(List<CommunityFollowsRepository.FollowRow> follows, String next) { return new ListResult(Status.OK, follows, next); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
    }

    public FollowResult follow(String firebaseUid, long communityId) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return FollowResult.userNotProvisioned();
        if (communities.findById(communityId).isEmpty()) return FollowResult.notFound();
        boolean created = follows.insertIfAbsent(actor.get().id, communityId);
        return FollowResult.ok(true, created);
    }

    public FollowResult unfollow(String firebaseUid, long communityId) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return FollowResult.userNotProvisioned();
        if (communities.findById(communityId).isEmpty()) return FollowResult.notFound();
        boolean deleted = follows.delete(actor.get().id, communityId);
        return FollowResult.ok(false, deleted);
    }

    public record FollowResult(Status status, boolean following, boolean changed) {
        static FollowResult ok(boolean following, boolean changed) { return new FollowResult(Status.OK, following, changed); }
        static FollowResult userNotProvisioned() { return new FollowResult(Status.USER_NOT_PROVISIONED, false, false); }
        static FollowResult notFound() { return new FollowResult(Status.NOT_FOUND, false, false); }
    }
}
