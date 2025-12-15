package com.looped.users;

import com.looped.shared.Pagination;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class FollowsService {
    private final FollowsRepository follows;
    private final UserRepository users;

    public FollowsService(FollowsRepository follows, UserRepository users) {
        this.follows = follows;
        this.users = users;
    }

    public ListResult followers(String firebaseUid, long targetUserId, String cursor, int limit) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return ListResult.notFound();
        if (!actor.get().companyId.equals(target.get().companyId)) return ListResult.forbidden();

        var cursorParts = decodeCursor(cursor);
        var rows = follows.findFollowers(targetUserId, actor.get().companyId, cursorParts.timestamp, cursorParts.userId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.user.id);
        }
        List<UserRepository.UserRow> usersList = rows.stream().map(r -> r.user).toList();
        return ListResult.ok(usersList, next);
    }

    public ListResult following(String firebaseUid, long targetUserId, String cursor, int limit) {
        var actor = provisionedUser(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return ListResult.notFound();
        if (!actor.get().companyId.equals(target.get().companyId)) return ListResult.forbidden();

        var cursorParts = decodeCursor(cursor);
        var rows = follows.findFollowing(targetUserId, actor.get().companyId, cursorParts.timestamp, cursorParts.userId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.user.id);
        }
        List<UserRepository.UserRow> usersList = rows.stream().map(r -> r.user).toList();
        return ListResult.ok(usersList, next);
    }

    private Optional<UserRepository.UserRow> provisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
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

    private record CursorParts(OffsetDateTime timestamp, Long userId) {}

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN }

    public record ListResult(Status status, List<UserRepository.UserRow> users, String nextCursor) {
        static ListResult ok(List<UserRepository.UserRow> users, String next) { return new ListResult(Status.OK, users, next); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static ListResult notFound() { return new ListResult(Status.NOT_FOUND, List.of(), null); }
        static ListResult forbidden() { return new ListResult(Status.FORBIDDEN, List.of(), null); }
    }
}
