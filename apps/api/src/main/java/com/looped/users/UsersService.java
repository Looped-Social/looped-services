package com.looped.users;

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

    public UsersService(UserRepository users, VerificationRepository verifications, PostRepository posts) {
        this.users = users;
        this.verifications = verifications;
        this.posts = posts;
    }

    public ProfileResult profile(String firebaseUid, long targetUserId) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ProfileResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return ProfileResult.notFound();
        if (!actor.get().companyId.equals(target.get().companyId)) return ProfileResult.forbidden();

        var verification = verifications.findByUserId(targetUserId).orElse(null);
        return ProfileResult.ok(new UserProfile(
                target.get().id,
                target.get().handle,
                target.get().companyId,
                target.get().createdAt,
                verification == null ? null : new Verification(verification.method, verification.verified, verification.verifiedAt)
        ));
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

    private Optional<UserRepository.UserRow> requireProvisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN }

    public record ProfileResult(Status status, UserProfile profile) {
        static ProfileResult ok(UserProfile profile) { return new ProfileResult(Status.OK, profile); }
        static ProfileResult userNotProvisioned() { return new ProfileResult(Status.USER_NOT_PROVISIONED, null); }
        static ProfileResult notFound() { return new ProfileResult(Status.NOT_FOUND, null); }
        static ProfileResult forbidden() { return new ProfileResult(Status.FORBIDDEN, null); }
    }

    public record UserProfile(long id, String handle, Long companyId, OffsetDateTime createdAt, Verification verification) {}

    public record Verification(String method, boolean verified, OffsetDateTime verifiedAt) {}

    public record PostsResult(Status status, List<PostRepository.PostRow> posts, String nextCursor) {
        static PostsResult ok(List<PostRepository.PostRow> posts, String next) { return new PostsResult(Status.OK, posts, next); }
        static PostsResult userNotProvisioned() { return new PostsResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static PostsResult notFound() { return new PostsResult(Status.NOT_FOUND, List.of(), null); }
        static PostsResult forbidden() { return new PostsResult(Status.FORBIDDEN, List.of(), null); }
    }
}

