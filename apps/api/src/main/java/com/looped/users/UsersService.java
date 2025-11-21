package com.looped.users;

import com.looped.comments.CommentsRepository;
import com.looped.posts.PostRepository;
import com.looped.shared.Pagination;
import com.looped.verification.VerificationRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class UsersService {
    private final UserRepository users;
    private final VerificationRepository verifications;
    private final PostRepository posts;
    private final CommentsRepository comments;

    public UsersService(UserRepository users, VerificationRepository verifications, PostRepository posts, CommentsRepository comments) {
        this.users = users;
        this.verifications = verifications;
        this.posts = posts;
        this.comments = comments;
    }

    public ProfileResult profile(String firebaseUid, long targetUserId) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ProfileResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return ProfileResult.notFound();
        if (!actor.get().companyId.equals(target.get().companyId)) return ProfileResult.forbidden();

        var verification = verifications.findByUserId(targetUserId).orElse(null);
        return ProfileResult.ok(buildProfile(target.get(), verification));
    }

    public PostsResult posts(String firebaseUid, long targetUserId, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return PostsResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return PostsResult.notFound();
        if (!actor.get().companyId.equals(target.get().companyId)) return PostsResult.forbidden();

        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        var rows = posts.findByAuthor(targetUserId, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return PostsResult.ok(rows, next);
    }

    public UpdateProfileResult updateProfile(String firebaseUid, String displayName, String bio, boolean isAnonymous) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return UpdateProfileResult.userNotProvisioned();
        users.updateProfile(actor.get().id, displayName, bio, isAnonymous);
        var updated = users.findById(actor.get().id).orElse(actor.get());
        var verification = verifications.findByUserId(actor.get().id).orElse(null);
        return UpdateProfileResult.ok(buildProfile(updated, verification));
    }

    public SearchResult search(String firebaseUid, String query, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return SearchResult.userNotProvisioned();
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = users.searchCompanyUsers(actor.get().companyId, query, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return SearchResult.ok(rows, next);
    }

    public SearchResult directory(String firebaseUid, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return SearchResult.userNotProvisioned();
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = users.listCompanyUsers(actor.get().companyId, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return SearchResult.ok(rows, next);
    }

    public CommentsResult comments(String firebaseUid, long targetUserId, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return CommentsResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return CommentsResult.notFound();
        if (!actor.get().companyId.equals(target.get().companyId)) return CommentsResult.forbidden();

        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = comments.findByUser(targetUserId, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return CommentsResult.ok(rows, next);
    }

    public Optional<UserProfile> currentProfile(String firebaseUid) {
        var user = requireProvisionedUser(firebaseUid);
        if (user.isEmpty()) return Optional.empty();
        var verification = verifications.findByUserId(user.get().id).orElse(null);
        return Optional.of(buildProfile(user.get(), verification));
    }

    private Optional<UserRepository.UserRow> requireProvisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
    }

    private UserProfile buildProfile(UserRepository.UserRow row, VerificationRepository.Row verification) {
        var verificationData = verification == null ? null : new Verification(verification.method, verification.verified, verification.verifiedAt);
        var stats = new ProfileStats(
                users.countFollowers(row.id),
                users.countFollowing(row.id),
                users.countPosts(row.id),
                users.countComments(row.id)
        );
        return new UserProfile(
                row.id,
                row.handle,
                row.displayName,
                row.bio,
                row.isAnonymous,
                row.companyId,
                row.createdAt,
                row.profileImageUrl,
                verificationData,
                stats
        );
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN }

    public record ProfileResult(Status status, UserProfile profile) {
        static ProfileResult ok(UserProfile profile) { return new ProfileResult(Status.OK, profile); }
        static ProfileResult userNotProvisioned() { return new ProfileResult(Status.USER_NOT_PROVISIONED, null); }
        static ProfileResult notFound() { return new ProfileResult(Status.NOT_FOUND, null); }
        static ProfileResult forbidden() { return new ProfileResult(Status.FORBIDDEN, null); }
    }

    public record UpdateProfileResult(Status status, UserProfile profile) {
        static UpdateProfileResult ok(UserProfile profile) { return new UpdateProfileResult(Status.OK, profile); }
        static UpdateProfileResult userNotProvisioned() { return new UpdateProfileResult(Status.USER_NOT_PROVISIONED, null); }
    }

    public record SearchResult(Status status, List<UserRepository.UserRow> users, String nextCursor) {
        static SearchResult ok(List<UserRepository.UserRow> users, String next) { return new SearchResult(Status.OK, users, next); }
        static SearchResult userNotProvisioned() { return new SearchResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
    }

    public record CommentsResult(Status status, List<com.looped.comments.CommentsRepository.CommentRow> comments, String nextCursor) {
        static CommentsResult ok(List<com.looped.comments.CommentsRepository.CommentRow> comments, String next) { return new CommentsResult(Status.OK, comments, next); }
        static CommentsResult userNotProvisioned() { return new CommentsResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static CommentsResult notFound() { return new CommentsResult(Status.NOT_FOUND, List.of(), null); }
        static CommentsResult forbidden() { return new CommentsResult(Status.FORBIDDEN, List.of(), null); }
    }

    public record UserProfile(long id, String handle, String displayName, String bio, boolean isAnonymous, Long companyId,
                              OffsetDateTime createdAt, String profileImageUrl, Verification verification, ProfileStats stats) {}

    public record ProfileStats(int followerCount, int followingCount, int postsCount, int commentsCount) {}

    public record Verification(String method, boolean verified, OffsetDateTime verifiedAt) {}

    public record PostsResult(Status status, List<PostRepository.PostRow> posts, String nextCursor) {
        static PostsResult ok(List<PostRepository.PostRow> posts, String next) { return new PostsResult(Status.OK, posts, next); }
        static PostsResult userNotProvisioned() { return new PostsResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static PostsResult notFound() { return new PostsResult(Status.NOT_FOUND, List.of(), null); }
        static PostsResult forbidden() { return new PostsResult(Status.FORBIDDEN, List.of(), null); }
    }
}
