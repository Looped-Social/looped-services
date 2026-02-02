package com.looped.posts;

import com.looped.anon.AnonProofService;
import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.SpecializationJoinsRepository;
import com.looped.notifications.NotificationPublisher;
import com.looped.principals.PrincipalRepository;
import com.looped.users.UserCommunityBanRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LikesService {
    private final LikesRepository likes;
    private final PostRepository posts;
    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final SpecializationJoinsRepository specializationJoins;
    private final PrincipalRepository principals;
    private final AnonProofService anonProofs;
    private final NotificationPublisher notifications;
    private final UserCommunityBanRepository communityBans;

    public LikesService(LikesRepository likes,
                        PostRepository posts,
                        UserRepository users,
                        CommunitiesRepository communities,
                        CommunityVerificationsRepository communityVerifications,
                        SpecializationJoinsRepository specializationJoins,
                        PrincipalRepository principals,
                        AnonProofService anonProofs,
                        NotificationPublisher notifications,
                        UserCommunityBanRepository communityBans) {
        this.likes = likes;
        this.posts = posts;
        this.users = users;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.specializationJoins = specializationJoins;
        this.principals = principals;
        this.anonProofs = anonProofs;
        this.notifications = notifications;
        this.communityBans = communityBans;
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
            if (p.get().communityId != null && communityBans.isBanned(u.get().id, p.get().communityId)) {
                return Result.communityBanned();
            }
            AccessStatus access = checkAccess(u.get().id, p.get().communityId);
            if (access == AccessStatus.NOT_VERIFIED) return Result.notVerified();
            if (access == AccessStatus.SPECIALIZATION_NOT_JOINED) return Result.specializationNotJoined();
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
            if (p.get().communityId != null && communityBans.isBanned(u.get().id, p.get().communityId)) {
                return UnlikeResult.communityBanned();
            }
            AccessStatus access = checkAccess(u.get().id, p.get().communityId);
            if (access == AccessStatus.NOT_VERIFIED) return UnlikeResult.notVerified();
            if (access == AccessStatus.SPECIALIZATION_NOT_JOINED) return UnlikeResult.specializationNotJoined();
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

    private AccessStatus checkAccess(long userId, Long communityId) {
        if (communityId == null) return AccessStatus.OK;
        var community = communities.findById(communityId).orElse(null);
        if (community == null || community.kind == null) return AccessStatus.OK;
        if ("specialization".equalsIgnoreCase(community.kind)) {
            if (!requiresSpecializationJoin(community.specializationType)) return AccessStatus.OK;
            return specializationJoins.exists(userId, communityId) ? AccessStatus.OK : AccessStatus.SPECIALIZATION_NOT_JOINED;
        }
        return communityVerifications.isVerified(userId, communityId) ? AccessStatus.OK : AccessStatus.NOT_VERIFIED;
    }

    private boolean requiresSpecializationJoin(String specializationType) {
        if (specializationType == null) return false;
        String t = specializationType.trim().toLowerCase(java.util.Locale.ROOT);
        return t.equals("major") || t.equals("field");
    }

    private enum AccessStatus { OK, NOT_VERIFIED, SPECIALIZATION_NOT_JOINED }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, INVALID_SIGNATURE, COMMUNITY_BANNED, NOT_VERIFIED, SPECIALIZATION_NOT_JOINED }
    public record Result(Status status, boolean created, int likesCount) {
        static Result ok(boolean created, int count) { return new Result(Status.OK, created, count); }
        static Result userNotProvisioned() { return new Result(Status.USER_NOT_PROVISIONED, false, 0); }
        static Result notFound() { return new Result(Status.NOT_FOUND, false, 0); }
        static Result invalidSignature() { return new Result(Status.INVALID_SIGNATURE, false, 0); }
        static Result communityBanned() { return new Result(Status.COMMUNITY_BANNED, false, 0); }
        static Result notVerified() { return new Result(Status.NOT_VERIFIED, false, 0); }
        static Result specializationNotJoined() { return new Result(Status.SPECIALIZATION_NOT_JOINED, false, 0); }
    }

    public record UnlikeResult(Status status, boolean deleted, int likesCount) {
        static UnlikeResult ok(boolean deleted, int count) { return new UnlikeResult(Status.OK, deleted, count); }
        static UnlikeResult userNotProvisioned() { return new UnlikeResult(Status.USER_NOT_PROVISIONED, false, 0); }
        static UnlikeResult notFound() { return new UnlikeResult(Status.NOT_FOUND, false, 0); }
        static UnlikeResult invalidSignature() { return new UnlikeResult(Status.INVALID_SIGNATURE, false, 0); }
        static UnlikeResult communityBanned() { return new UnlikeResult(Status.COMMUNITY_BANNED, false, 0); }
        static UnlikeResult notVerified() { return new UnlikeResult(Status.NOT_VERIFIED, false, 0); }
        static UnlikeResult specializationNotJoined() { return new UnlikeResult(Status.SPECIALIZATION_NOT_JOINED, false, 0); }
    }
}
