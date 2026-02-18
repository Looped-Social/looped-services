package com.looped.posts;

import com.looped.anon.AnonProofService;
import com.looped.notifications.NotificationPublisher;
import com.looped.principals.PrincipalRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LikesService {
    private final LikesRepository likes;
    private final PostRepository posts;
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final AnonProofService anonProofs;
    private final NotificationPublisher notifications;
    private final CommunityInteractionLockService interactionLocks;

    public LikesService(LikesRepository likes,
                        PostRepository posts,
                        UserRepository users,
                        PrincipalRepository principals,
                        AnonProofService anonProofs,
                        NotificationPublisher notifications,
                        CommunityInteractionLockService interactionLocks) {
        this.likes = likes;
        this.posts = posts;
        this.users = users;
        this.principals = principals;
        this.anonProofs = anonProofs;
        this.notifications = notifications;
        this.interactionLocks = interactionLocks;
    }

    @Transactional
    public Result like(String firebaseUid, long postId, AnonProofService.AnonActionProof anonProof) {
        var p = posts.findById(postId);
        if (p.isEmpty()) return Result.notFound();

        long actorPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            var verified = anonProofs.verifyActionScoped(anonProof, "like", postId, p.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return Result.invalidSignature();
            actorPrincipalId = verified.actor().principalId();
        } else {
            if (firebaseUid == null) return Result.userNotProvisioned();
            var u = users.findByFirebaseUid(firebaseUid);
            if (u.isEmpty()) return Result.userNotProvisioned();
            var lock = interactionLocks.evaluate(u.get().id, u.get().companyId, p.get().communityId);
            if (!lock.canInteract()) return Result.fromLock(lock);
            var principal = principals.createForUser(u.get().id);
            actorPrincipalId = principal.id;
        }
        if (p.get().visibility != null && !p.get().visibility.equalsIgnoreCase("public")
                && actorPrincipalId != p.get().authorPrincipalId) {
            return Result.notFound();
        }

        boolean created = likes.insertIfAbsent(actorPrincipalId, postId);
        if (created) {
            likes.incrementPostLikes(postId);
            try {
                notifications.notifyPostLike(p.get(), actorPrincipalId);
            } catch (RuntimeException ignored) {}
        }
        var current = posts.findById(postId).orElseThrow();
        return Result.ok(created, current.likesCount);
    }

    @Transactional
    public UnlikeResult unlike(String firebaseUid, long postId, AnonProofService.AnonActionProof anonProof) {
        var p = posts.findById(postId);
        if (p.isEmpty()) return UnlikeResult.notFound();

        long actorPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            var verified = anonProofs.verifyActionScoped(anonProof, "unlike", postId, p.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return UnlikeResult.invalidSignature();
            actorPrincipalId = verified.actor().principalId();
        } else {
            if (firebaseUid == null) return UnlikeResult.userNotProvisioned();
            var u = users.findByFirebaseUid(firebaseUid);
            if (u.isEmpty()) return UnlikeResult.userNotProvisioned();
            var lock = interactionLocks.evaluate(u.get().id, u.get().companyId, p.get().communityId);
            if (!lock.canInteract()) return UnlikeResult.fromLock(lock);
            var principal = principals.createForUser(u.get().id);
            actorPrincipalId = principal.id;
        }
        if (p.get().visibility != null && !p.get().visibility.equalsIgnoreCase("public")
                && actorPrincipalId != p.get().authorPrincipalId) {
            return UnlikeResult.notFound();
        }

        boolean deleted = likes.deleteIfPresent(actorPrincipalId, postId);
        if (deleted) {
            likes.decrementPostLikes(postId);
        }
        var current = posts.findById(postId).orElseThrow();
        return UnlikeResult.ok(deleted, current.likesCount);
    }

    public enum Status {
        OK,
        USER_NOT_PROVISIONED,
        NOT_FOUND,
        INVALID_SIGNATURE,
        COMMUNITY_BANNED,
        NOT_VERIFIED,
        VERIFICATION_EXPIRED,
        SPECIALIZATION_NOT_JOINED,
        SPECIALIZATION_VERIFICATION_REQUIRED
    }
    public record Result(Status status, boolean created, int likesCount, CommunityInteractionLockService.LockEvaluation lock) {
        static Result ok(boolean created, int count) { return new Result(Status.OK, created, count, null); }
        static Result userNotProvisioned() { return new Result(Status.USER_NOT_PROVISIONED, false, 0, null); }
        static Result notFound() { return new Result(Status.NOT_FOUND, false, 0, null); }
        static Result invalidSignature() { return new Result(Status.INVALID_SIGNATURE, false, 0, null); }
        static Result fromLock(CommunityInteractionLockService.LockEvaluation lock) {
            if (lock == null) return notFound();
            return switch (lock.errorCode()) {
                case "community_banned" -> new Result(Status.COMMUNITY_BANNED, false, 0, lock);
                case "verification_expired" -> new Result(Status.VERIFICATION_EXPIRED, false, 0, lock);
                case "specialization_not_joined" -> new Result(Status.SPECIALIZATION_NOT_JOINED, false, 0, lock);
                case "specialization_verification_required" -> new Result(Status.SPECIALIZATION_VERIFICATION_REQUIRED, false, 0, lock);
                default -> new Result(Status.NOT_VERIFIED, false, 0, lock);
            };
        }
    }

    public record UnlikeResult(Status status, boolean deleted, int likesCount, CommunityInteractionLockService.LockEvaluation lock) {
        static UnlikeResult ok(boolean deleted, int count) { return new UnlikeResult(Status.OK, deleted, count, null); }
        static UnlikeResult userNotProvisioned() { return new UnlikeResult(Status.USER_NOT_PROVISIONED, false, 0, null); }
        static UnlikeResult notFound() { return new UnlikeResult(Status.NOT_FOUND, false, 0, null); }
        static UnlikeResult invalidSignature() { return new UnlikeResult(Status.INVALID_SIGNATURE, false, 0, null); }
        static UnlikeResult fromLock(CommunityInteractionLockService.LockEvaluation lock) {
            if (lock == null) return notFound();
            return switch (lock.errorCode()) {
                case "community_banned" -> new UnlikeResult(Status.COMMUNITY_BANNED, false, 0, lock);
                case "verification_expired" -> new UnlikeResult(Status.VERIFICATION_EXPIRED, false, 0, lock);
                case "specialization_not_joined" -> new UnlikeResult(Status.SPECIALIZATION_NOT_JOINED, false, 0, lock);
                case "specialization_verification_required" -> new UnlikeResult(Status.SPECIALIZATION_VERIFICATION_REQUIRED, false, 0, lock);
                default -> new UnlikeResult(Status.NOT_VERIFIED, false, 0, lock);
            };
        }
    }
}
