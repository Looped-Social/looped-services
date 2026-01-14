package com.looped.users;

import com.looped.anon.AnonProofService;
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
    private final AnonProofService anonProofs;

    public BlocksService(BlocksRepository blocks,
                         UserRepository users,
                         PrincipalRepository principals,
                         PrincipalProfilesRepository profiles,
                         AnonProofService anonProofs) {
        this.blocks = blocks;
        this.users = users;
        this.principals = principals;
        this.profiles = profiles;
        this.anonProofs = anonProofs;
    }

    public BlockResult blockUser(String firebaseUid, long targetUserId, AnonProofService.AnonActionProof anonProof) {
        var target = users.findById(targetUserId);
        if (target.isEmpty()) return BlockResult.notFound();

        var targetPrincipal = principals.createForUser(targetUserId);

        long actorPrincipalId = resolveActorPrincipalId(firebaseUid, anonProof, "block", targetPrincipal.id, targetUserId);
        if (actorPrincipalId == 0) return BlockResult.userNotProvisioned();
        if (actorPrincipalId == -1) return BlockResult.invalidSignature();

        if (actorPrincipalId == targetPrincipal.id) return BlockResult.invalidTarget();
        boolean created = blocks.insertIfAbsent(actorPrincipalId, targetPrincipal.id);
        return BlockResult.ok(true, created);
    }

    public BlockResult unblockUser(String firebaseUid, long targetUserId, AnonProofService.AnonActionProof anonProof) {
        var target = users.findById(targetUserId);
        if (target.isEmpty()) return BlockResult.notFound();

        var targetPrincipal = principals.createForUser(targetUserId);

        long actorPrincipalId = resolveActorPrincipalId(firebaseUid, anonProof, "unblock", targetPrincipal.id, targetUserId);
        if (actorPrincipalId == 0) return BlockResult.userNotProvisioned();
        if (actorPrincipalId == -1) return BlockResult.invalidSignature();

        if (actorPrincipalId == targetPrincipal.id) return BlockResult.invalidTarget();
        boolean deleted = blocks.delete(actorPrincipalId, targetPrincipal.id);
        return BlockResult.ok(false, deleted);
    }

    public BlockResult blockPrincipal(String firebaseUid, long targetPrincipalId, AnonProofService.AnonActionProof anonProof) {
        if (principals.findById(targetPrincipalId).isEmpty()) return BlockResult.notFound();

        long actorPrincipalId = resolveActorPrincipalId(firebaseUid, anonProof, "block", targetPrincipalId, null);
        if (actorPrincipalId == 0) return BlockResult.userNotProvisioned();
        if (actorPrincipalId == -1) return BlockResult.invalidSignature();

        if (actorPrincipalId == targetPrincipalId) return BlockResult.invalidTarget();
        boolean created = blocks.insertIfAbsent(actorPrincipalId, targetPrincipalId);
        return BlockResult.ok(true, created);
    }

    public BlockResult unblockPrincipal(String firebaseUid, long targetPrincipalId, AnonProofService.AnonActionProof anonProof) {
        if (principals.findById(targetPrincipalId).isEmpty()) return BlockResult.notFound();

        long actorPrincipalId = resolveActorPrincipalId(firebaseUid, anonProof, "unblock", targetPrincipalId, null);
        if (actorPrincipalId == 0) return BlockResult.userNotProvisioned();
        if (actorPrincipalId == -1) return BlockResult.invalidSignature();

        if (actorPrincipalId == targetPrincipalId) return BlockResult.invalidTarget();
        boolean deleted = blocks.delete(actorPrincipalId, targetPrincipalId);
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

    private long resolveActorPrincipalId(String firebaseUid, AnonProofService.AnonActionProof anonProof, String action, long targetId, Long alternateTargetId) {
        if (anonProof != null && anonProof.anonProfileId() != null) {
            var verified = alternateTargetId == null
                    ? anonProofs.verifyAction(anonProof, action, targetId)
                    : anonProofs.verifyActionAnyTarget(anonProof, action, targetId, alternateTargetId);
            return verified.status() == AnonProofService.Status.OK ? verified.actor().principalId() : -1;
        }
        if (firebaseUid == null) return 0;
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty()) return 0;
        return principals.createForUser(actor.get().id).id;
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, INVALID_TARGET, INVALID_SIGNATURE }

    public record BlockResult(Status status, boolean blocked, boolean changed) {
        static BlockResult ok(boolean blocked, boolean changed) { return new BlockResult(Status.OK, blocked, changed); }
        static BlockResult userNotProvisioned() { return new BlockResult(Status.USER_NOT_PROVISIONED, false, false); }
        static BlockResult notFound() { return new BlockResult(Status.NOT_FOUND, false, false); }
        static BlockResult invalidTarget() { return new BlockResult(Status.INVALID_TARGET, false, false); }
        static BlockResult invalidSignature() { return new BlockResult(Status.INVALID_SIGNATURE, false, false); }
    }

    public record ListResult(Status status, java.util.List<PrincipalProfilesRepository.PrincipalProfileRow> users, String nextCursor) {
        static ListResult ok(java.util.List<PrincipalProfilesRepository.PrincipalProfileRow> users, String next) {
            return new ListResult(Status.OK, users, next);
        }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, java.util.List.of(), null); }
    }
}
