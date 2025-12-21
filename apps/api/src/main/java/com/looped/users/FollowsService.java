package com.looped.users;

import com.looped.anon.AnonProofService;
import com.looped.principals.PrincipalProfilesRepository;
import com.looped.principals.PrincipalRepository;
import com.looped.shared.Pagination;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;

@Service
public class FollowsService {
    private final FollowsRepository follows;
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final PrincipalProfilesRepository profiles;
    private final AnonProofService anonProofs;

    public FollowsService(FollowsRepository follows,
                          UserRepository users,
                          PrincipalRepository principals,
                          PrincipalProfilesRepository profiles,
                          AnonProofService anonProofs) {
        this.follows = follows;
        this.users = users;
        this.principals = principals;
        this.profiles = profiles;
        this.anonProofs = anonProofs;
    }

    public ListResult followers(String firebaseUid, long targetUserId, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return ListResult.notFound();

        var targetPrincipal = principals.createForUser(targetUserId);

        var cursorParts = decodeCursor(cursor);
        var rows = profiles.followers(targetPrincipal.id, cursorParts.timestamp, cursorParts.principalId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.followCreatedAt, last.principalId);
        }
        return ListResult.ok(rows, next);
    }

    public ListResult following(String firebaseUid, long targetUserId, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty()) return ListResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return ListResult.notFound();

        var targetPrincipal = principals.createForUser(targetUserId);

        var cursorParts = decodeCursor(cursor);
        var rows = profiles.following(targetPrincipal.id, cursorParts.timestamp, cursorParts.principalId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.followCreatedAt, last.principalId);
        }
        return ListResult.ok(rows, next);
    }

    public FollowResult follow(String firebaseUid, long targetUserId, AnonProofService.AnonActionProof anonProof) {
        var actorUser = users.findByFirebaseUid(firebaseUid);
        if (actorUser.isEmpty()) return FollowResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return FollowResult.notFound();

        var targetPrincipal = principals.createForUser(targetUserId);

        long actorPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            var verified = anonProofs.verifyAction(anonProof, "follow", targetPrincipal.id);
            if (verified.status() != AnonProofService.Status.OK) return FollowResult.invalidSignature();
            actorPrincipalId = verified.actor().principalId();
        } else {
            var principal = principals.createForUser(actorUser.get().id);
            actorPrincipalId = principal.id;
        }

        if (actorPrincipalId == targetPrincipal.id) return FollowResult.invalidTarget();
        boolean created = follows.insertIfAbsent(actorPrincipalId, targetPrincipal.id);
        return FollowResult.ok(true, created);
    }

    public FollowResult unfollow(String firebaseUid, long targetUserId, AnonProofService.AnonActionProof anonProof) {
        var actorUser = users.findByFirebaseUid(firebaseUid);
        if (actorUser.isEmpty()) return FollowResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return FollowResult.notFound();

        var targetPrincipal = principals.createForUser(targetUserId);

        long actorPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            var verified = anonProofs.verifyAction(anonProof, "unfollow", targetPrincipal.id);
            if (verified.status() != AnonProofService.Status.OK) return FollowResult.invalidSignature();
            actorPrincipalId = verified.actor().principalId();
        } else {
            var principal = principals.createForUser(actorUser.get().id);
            actorPrincipalId = principal.id;
        }

        if (actorPrincipalId == targetPrincipal.id) return FollowResult.invalidTarget();
        boolean deleted = follows.delete(actorPrincipalId, targetPrincipal.id);
        return FollowResult.ok(false, deleted);
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

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, INVALID_TARGET, INVALID_SIGNATURE }
    public record FollowResult(Status status, boolean following, boolean changed) {
        static FollowResult ok(boolean following, boolean changed) { return new FollowResult(Status.OK, following, changed); }
        static FollowResult userNotProvisioned() { return new FollowResult(Status.USER_NOT_PROVISIONED, false, false); }
        static FollowResult notFound() { return new FollowResult(Status.NOT_FOUND, false, false); }
        static FollowResult invalidTarget() { return new FollowResult(Status.INVALID_TARGET, false, false); }
        static FollowResult invalidSignature() { return new FollowResult(Status.INVALID_SIGNATURE, false, false); }
    }

    public record ListResult(Status status, java.util.List<com.looped.principals.PrincipalProfilesRepository.PrincipalProfileRow> users, String nextCursor) {
        static ListResult ok(java.util.List<com.looped.principals.PrincipalProfilesRepository.PrincipalProfileRow> users, String next) { return new ListResult(Status.OK, users, next); }
        static ListResult userNotProvisioned() { return new ListResult(Status.USER_NOT_PROVISIONED, java.util.List.of(), null); }
        static ListResult notFound() { return new ListResult(Status.NOT_FOUND, java.util.List.of(), null); }
    }
}
