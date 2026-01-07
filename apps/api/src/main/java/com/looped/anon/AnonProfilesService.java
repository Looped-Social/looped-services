package com.looped.anon;

import com.looped.communities.CommunitiesRepository;
import com.looped.comments.CommentsRepository;
import com.looped.posts.LikesRepository;
import com.looped.posts.PostRepository;
import com.looped.posts.PostStateService;
import com.looped.posts.SavedPostsRepository;
import com.looped.principals.PrincipalProfilesRepository;
import com.looped.principals.PrincipalRepository;
import com.looped.principals.PrincipalStatsRepository;
import com.looped.shared.Pagination;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class AnonProfilesService {
    private final UserRepository users;
    private final AnonymousProfilesRepository profiles;
    private final PrincipalRepository principals;
    private final PrincipalStatsRepository stats;
    private final PostRepository posts;
    private final PrincipalProfilesRepository follows;
    private final LikesRepository likes;
    private final SavedPostsRepository savedPosts;
    private final PostStateService postState;
    private final CommentsRepository comments;
    private final CommunitiesRepository communities;
    private final AnonIssuerRepository issuers;
    private final AnonProofService proofs;

    public AnonProfilesService(UserRepository users,
                               AnonymousProfilesRepository profiles,
                               PrincipalRepository principals,
                               PrincipalStatsRepository stats,
                               PostRepository posts,
                               PrincipalProfilesRepository follows,
                               LikesRepository likes,
                               SavedPostsRepository savedPosts,
                               PostStateService postState,
                               CommentsRepository comments,
                               CommunitiesRepository communities,
                               AnonIssuerRepository issuers,
                               AnonProofService proofs) {
        this.users = users;
        this.profiles = profiles;
        this.principals = principals;
        this.stats = stats;
        this.posts = posts;
        this.follows = follows;
        this.likes = likes;
        this.savedPosts = savedPosts;
        this.postState = postState;
        this.comments = comments;
        this.communities = communities;
        this.issuers = issuers;
        this.proofs = proofs;
    }

    public ProfileResult profile(String firebaseUid, long anonProfileId) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return ProfileResult.userNotProvisioned();
        var profile = profiles.findById(anonProfileId);
        if (profile.isEmpty()) return ProfileResult.notFound();
        if (profile.get().companyId != null && !actor.get().companyId.equals(profile.get().companyId)) {
            return ProfileResult.forbidden();
        }
        var principal = principals.createForAnon(anonProfileId);
        var statsBlock = new ProfileStats(
                stats.countFollowers(principal.id),
                stats.countFollowing(principal.id),
                stats.countPosts(principal.id)
        );
        DisplayCommunity displayCommunity = resolveDisplayCommunity(profile.get());
        return ProfileResult.ok(new AnonProfile(
                profile.get().id,
                profile.get().handle,
                profile.get().companyId,
                profile.get().createdAt,
                statsBlock,
                displayCommunity
        ));
    }

    public UpdateDisplayCommunityResult updateDisplayCommunity(long anonProfileId, Long communityId,
                                                               AnonProofService.AnonActionProof anonProof) {
        var profile = profiles.findById(anonProfileId);
        if (profile.isEmpty()) return UpdateDisplayCommunityResult.notFound();
        if (anonProof == null || anonProof.anonProfileId() == null) return UpdateDisplayCommunityResult.invalidAnonProof();
        if (!anonProfileIdEquals(anonProfileId, anonProof.anonProfileId())) {
            return UpdateDisplayCommunityResult.invalidAnonProof();
        }

        if (communityId != null) {
            var community = communities.findById(communityId);
            if (community.isEmpty()) return UpdateDisplayCommunityResult.communityNotFound();
            var verified = proofs.verifyActionScoped(anonProof, "anon_display_community", anonProfileId, communityId);
            if (verified.status() != AnonProofService.Status.OK) return UpdateDisplayCommunityResult.invalidAnonProof();
            profiles.updateDisplayCommunity(anonProfileId, communityId, anonProof.anonCertKid());
        } else {
            var verified = proofs.verifyAction(anonProof, "anon_display_community", anonProfileId);
            if (verified.status() != AnonProofService.Status.OK) return UpdateDisplayCommunityResult.invalidAnonProof();
            profiles.updateDisplayCommunity(anonProfileId, null, null);
        }

        var updated = profiles.findById(anonProfileId).orElseThrow();
        var principal = principals.createForAnon(anonProfileId);
        DisplayCommunity displayCommunity = resolveDisplayCommunity(updated);
        var statsBlock = new ProfileStats(
                stats.countFollowers(principal.id),
                stats.countFollowing(principal.id),
                stats.countPosts(principal.id)
        );
        return UpdateDisplayCommunityResult.ok(new AnonProfile(
                updated.id,
                updated.handle,
                updated.companyId,
                updated.createdAt,
                statsBlock,
                displayCommunity
        ));
    }

    private DisplayCommunity resolveDisplayCommunity(AnonymousProfilesRepository.AnonymousProfileRow profile) {
        if (profile.displayCommunityId == null || profile.displayCommunityCertKid == null) return null;
        var issuer = issuers.findByKid(profile.displayCommunityCertKid);
        if (issuer.isEmpty()) return null;
        var issuerRow = issuer.get();
        if (issuerRow.expiresAt != null && issuerRow.expiresAt.isBefore(OffsetDateTime.now())) return null;
        if (!"community".equals(issuerRow.scopeKind) || issuerRow.scopeId == null
                || !issuerRow.scopeId.equals(profile.displayCommunityId)) {
            return null;
        }
        var community = communities.findById(profile.displayCommunityId);
        if (community.isEmpty()) return null;
        return new DisplayCommunity(
                community.get().id,
                community.get().name,
                community.get().kind,
                community.get().specializationType
        );
    }

    private boolean anonProfileIdEquals(long expected, long provided) {
        return expected == provided;
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

    public PostsResult posts(String firebaseUid, long anonProfileId, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return PostsResult.userNotProvisioned();
        var profile = profiles.findById(anonProfileId);
        if (profile.isEmpty()) return PostsResult.notFound();
        if (profile.get().companyId != null && !actor.get().companyId.equals(profile.get().companyId)) {
            return PostsResult.forbidden();
        }
        var principal = principals.createForAnon(anonProfileId);

        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        var rows = posts.findByAuthorPrincipal(principal.id, cTs, cId, limit);
        var viewerPrincipal = principals.createForUser(actor.get().id);
        postState.applyForPrincipal(viewerPrincipal.id, rows);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return PostsResult.ok(rows, next);
    }

    public AnonPostListResult likedPosts(long anonProfileId, String cursor, int limit, AnonProofService.AnonActionProof anonProof) {
        if (anonProof == null || anonProof.anonProfileId() == null) return AnonPostListResult.invalidAnonProof();
        if (!anonProfileIdEquals(anonProfileId, anonProof.anonProfileId())) return AnonPostListResult.invalidAnonProof();
        var verified = proofs.verifyAction(anonProof, "anon_posts_liked", anonProfileId);
        if (verified.status() != AnonProofService.Status.OK) return AnonPostListResult.invalidAnonProof();

        var cursorParts = decodeCursor(cursor);
        var rows = likes.findLikedPosts(verified.actor().principalId(), cursorParts.timestamp, cursorParts.postId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.likedAt, last.post.id);
        }
        var posts = rows.stream().map(r -> r.post).toList();
        postState.applyForPrincipal(verified.actor().principalId(), posts);
        return AnonPostListResult.ok(posts, next);
    }

    public AnonPostListResult savedPosts(long anonProfileId, String cursor, int limit, AnonProofService.AnonActionProof anonProof) {
        if (anonProof == null || anonProof.anonProfileId() == null) return AnonPostListResult.invalidAnonProof();
        if (!anonProfileIdEquals(anonProfileId, anonProof.anonProfileId())) return AnonPostListResult.invalidAnonProof();
        var verified = proofs.verifyAction(anonProof, "anon_posts_saved", anonProfileId);
        if (verified.status() != AnonProofService.Status.OK) return AnonPostListResult.invalidAnonProof();

        var cursorParts = decodeCursor(cursor);
        var rows = savedPosts.findSavedPosts(verified.actor().principalId(), cursorParts.timestamp, cursorParts.postId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.savedAt, last.post.id);
        }
        var posts = rows.stream().map(r -> r.post).toList();
        postState.applyForPrincipal(verified.actor().principalId(), posts);
        return AnonPostListResult.ok(posts, next);
    }

    public RepliesResult replies(long anonProfileId, String cursor, int limit, AnonProofService.AnonActionProof anonProof) {
        if (anonProof == null || anonProof.anonProfileId() == null) return RepliesResult.invalidAnonProof();
        if (!anonProfileIdEquals(anonProfileId, anonProof.anonProfileId())) return RepliesResult.invalidAnonProof();
        var verified = proofs.verifyAction(anonProof, "comment_anon_replies", anonProfileId);
        if (verified.status() != AnonProofService.Status.OK) return RepliesResult.invalidAnonProof();

        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = comments.findByAuthorPrincipalWithView(verified.actor().principalId(), verified.actor().principalId(), cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1).comment;
            next = Pagination.encode(last.createdAt, last.id);
        }
        return RepliesResult.ok(rows, next);
    }

    public RepliesForViewerResult repliesForViewer(String firebaseUid, long anonProfileId, String cursor, int limit) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return RepliesForViewerResult.userNotProvisioned();
        var profile = profiles.findById(anonProfileId);
        if (profile.isEmpty()) return RepliesForViewerResult.notFound();
        if (profile.get().companyId != null && !actor.get().companyId.equals(profile.get().companyId)) {
            return RepliesForViewerResult.forbidden();
        }
        var targetPrincipal = principals.createForAnon(anonProfileId);
        var viewerPrincipal = principals.createForUser(actor.get().id);

        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = comments.findByAuthorPrincipalWithView(targetPrincipal.id, viewerPrincipal.id, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1).comment;
            next = Pagination.encode(last.createdAt, last.id);
        }
        return RepliesForViewerResult.ok(rows, next);
    }

    public FollowsResult followers(String firebaseUid, long anonProfileId, String cursor, int limit) {
        return followList(firebaseUid, anonProfileId, cursor, limit, true);
    }

    public FollowsResult following(String firebaseUid, long anonProfileId, String cursor, int limit) {
        return followList(firebaseUid, anonProfileId, cursor, limit, false);
    }

    private FollowsResult followList(String firebaseUid, long anonProfileId, String cursor, int limit, boolean followersList) {
        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) return FollowsResult.userNotProvisioned();
        var profile = profiles.findById(anonProfileId);
        if (profile.isEmpty()) return FollowsResult.notFound();
        if (profile.get().companyId != null && !actor.get().companyId.equals(profile.get().companyId)) {
            return FollowsResult.forbidden();
        }
        var principal = principals.createForAnon(anonProfileId);

        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        List<PrincipalProfilesRepository.PrincipalProfileRow> rows;
        if (followersList) {
            rows = follows.followers(principal.id, cTs, cId, limit);
        } else {
            rows = follows.following(principal.id, cTs, cId, limit);
        }
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.followCreatedAt, last.principalId);
        }
        return FollowsResult.ok(rows, next);
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN }

    public record ProfileResult(Status status, AnonProfile profile) {
        static ProfileResult ok(AnonProfile profile) { return new ProfileResult(Status.OK, profile); }
        static ProfileResult userNotProvisioned() { return new ProfileResult(Status.USER_NOT_PROVISIONED, null); }
        static ProfileResult notFound() { return new ProfileResult(Status.NOT_FOUND, null); }
        static ProfileResult forbidden() { return new ProfileResult(Status.FORBIDDEN, null); }
    }

    public record UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus status, AnonProfile profile) {
        static UpdateDisplayCommunityResult ok(AnonProfile profile) {
            return new UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus.OK, profile);
        }
        static UpdateDisplayCommunityResult notFound() {
            return new UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus.NOT_FOUND, null);
        }
        static UpdateDisplayCommunityResult invalidAnonProof() {
            return new UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus.INVALID_ANON_PROOF, null);
        }
        static UpdateDisplayCommunityResult communityNotFound() {
            return new UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus.COMMUNITY_NOT_FOUND, null);
        }
    }

    public record PostsResult(Status status, List<PostRepository.PostRow> posts, String nextCursor) {
        static PostsResult ok(List<PostRepository.PostRow> posts, String next) { return new PostsResult(Status.OK, posts, next); }
        static PostsResult userNotProvisioned() { return new PostsResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static PostsResult notFound() { return new PostsResult(Status.NOT_FOUND, List.of(), null); }
        static PostsResult forbidden() { return new PostsResult(Status.FORBIDDEN, List.of(), null); }
    }

    public record AnonPostListResult(AnonStatus status, List<PostRepository.PostRow> posts, String nextCursor) {
        static AnonPostListResult ok(List<PostRepository.PostRow> posts, String next) { return new AnonPostListResult(AnonStatus.OK, posts, next); }
        static AnonPostListResult invalidAnonProof() { return new AnonPostListResult(AnonStatus.INVALID_ANON_PROOF, List.of(), null); }
    }

    public record RepliesResult(AnonStatus status, List<CommentsRepository.CommentViewRow> comments, String nextCursor) {
        static RepliesResult ok(List<CommentsRepository.CommentViewRow> comments, String nextCursor) { return new RepliesResult(AnonStatus.OK, comments, nextCursor); }
        static RepliesResult invalidAnonProof() { return new RepliesResult(AnonStatus.INVALID_ANON_PROOF, List.of(), null); }
    }

    public record RepliesForViewerResult(Status status, List<CommentsRepository.CommentViewRow> comments, String nextCursor) {
        static RepliesForViewerResult ok(List<CommentsRepository.CommentViewRow> comments, String nextCursor) { return new RepliesForViewerResult(Status.OK, comments, nextCursor); }
        static RepliesForViewerResult userNotProvisioned() { return new RepliesForViewerResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static RepliesForViewerResult notFound() { return new RepliesForViewerResult(Status.NOT_FOUND, List.of(), null); }
        static RepliesForViewerResult forbidden() { return new RepliesForViewerResult(Status.FORBIDDEN, List.of(), null); }
    }

    public record FollowsResult(Status status, List<PrincipalProfilesRepository.PrincipalProfileRow> principals, String nextCursor) {
        static FollowsResult ok(List<PrincipalProfilesRepository.PrincipalProfileRow> principals, String next) { return new FollowsResult(Status.OK, principals, next); }
        static FollowsResult userNotProvisioned() { return new FollowsResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static FollowsResult notFound() { return new FollowsResult(Status.NOT_FOUND, List.of(), null); }
        static FollowsResult forbidden() { return new FollowsResult(Status.FORBIDDEN, List.of(), null); }
    }

    public record AnonProfile(long id, String handle, Long companyId, OffsetDateTime createdAt,
                              ProfileStats stats, DisplayCommunity displayCommunity) {}

    public record DisplayCommunity(long id, String name, String kind, String specializationType) {}

    public record ProfileStats(int followerCount, int followingCount, int postsCount) {}

    public enum UpdateDisplayCommunityStatus { OK, NOT_FOUND, INVALID_ANON_PROOF, COMMUNITY_NOT_FOUND }

    public enum AnonStatus { OK, INVALID_ANON_PROOF }

    private record CursorParts(OffsetDateTime timestamp, Long postId) {}
}
