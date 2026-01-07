package com.looped.users;

import com.looped.principals.PrincipalProfilesRepository;
import com.looped.principals.PrincipalRepository;
import com.looped.shared.Pagination;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class BlocksService {
    private final BlocksRepository blocks;
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final PrincipalProfilesRepository profiles;

    public BlocksService(BlocksRepository blocks,
                         UserRepository users,
                         PrincipalRepository principals,
                         PrincipalProfilesRepository profiles) {
        this.blocks = blocks;
        this.users = users;
        this.principals = principals;
        this.profiles = profiles;
    }

    public BlockResult block(String firebaseUid, long targetUserId) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty()) return BlockResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return BlockResult.notFound();

        var actorPrincipal = principals.createForUser(actor.get().id);
        var targetPrincipal = principals.createForUser(targetUserId);

        if (actorPrincipal.id == targetPrincipal.id) return BlockResult.invalidTarget();
        boolean created = blocks.insertIfAbsent(actorPrincipal.id, targetPrincipal.id);
        return BlockResult.ok(true, created);
    }

    public BlockResult unblock(String firebaseUid, long targetUserId) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty()) return BlockResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return BlockResult.notFound();

        var actorPrincipal = principals.createForUser(actor.get().id);
        var targetPrincipal = principals.createForUser(targetUserId);

        if (actorPrincipal.id == targetPrincipal.id) return BlockResult.invalidTarget();
        boolean deleted = blocks.delete(actorPrincipal.id, targetPrincipal.id);
        return BlockResult.ok(false, deleted);
    }

    public ListResult blocked(String firebaseUid, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();

        var actorPrincipal = principals.createForUser(actor.get().id);

        var cursorParts = decodeCursor(cursor);
        var rows = profiles.blocked(actorPrincipal.id, cursorParts.timestamp, cursorParts.principalId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.followCreatedAt, last.principalId);
        }
        return ListResult.ok(rows, next);
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

    private record CursorParts(OffsetDateTime timestamp, Long principalId) {}

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, INVALID_TARGET }

    public record BlockResult(Status status, boolean blocked, boolean changed) {
        static BlockResult ok(boolean blocked, boolean changed) { return new BlockResult(Status.OK, blocked, changed); }
        static BlockResult userNotProvisioned() { return new BlockResult(Status.USER_NOT_PROVISIONED, false, false); }
        static BlockResult notFound() { return new BlockResult(Status.NOT_FOUND, false, false); }
        static BlockResult invalidTarget() { return new BlockResult(Status.INVALID_TARGET, false, false); }
    }

    public record ListResult(Status status, java.util.List<PrincipalProfilesRepository.PrincipalProfileRow> users, String nextCursor) {
        static ListResult ok(java.util.List<PrincipalProfilesRepository.PrincipalProfileRow> users, String next) {
            return new ListResult(Status.OK, users, next);
        }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, java.util.List.of(), null); }
    }
}
