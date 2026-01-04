package com.looped.users;

import com.looped.auth.FirebaseAdminService;
import com.looped.comments.CommentsRepository;
import com.looped.companies.CompanyRepository;
import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.posts.PostRepository;
import com.looped.shared.Pagination;
import com.looped.verification.VerificationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class UsersService {
    private final UserRepository users;
    private final VerificationRepository verifications;
    private final PostRepository posts;
    private final CommentsRepository comments;
    private final CompanyRepository companies;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final FirebaseAdminService firebaseAdmin;
    private final int deactivatedRetentionDays;
    private final int usernameTombstoneDays;
    private final String defaultCompanyDomain;

    public UsersService(UserRepository users,
                        VerificationRepository verifications,
                        PostRepository posts,
                        CommentsRepository comments,
                        CompanyRepository companies,
                        CommunitiesRepository communities,
                        CommunityVerificationsRepository communityVerifications,
                        FirebaseAdminService firebaseAdmin,
                        @Value("${retention.deactivated-days:90}") int deactivatedRetentionDays,
                        @Value("${retention.username-tombstone-days:14}") int usernameTombstoneDays,
                        @Value("${onboarding.default-company-domain:looped.global}") String defaultCompanyDomain) {
        this.users = users;
        this.verifications = verifications;
        this.posts = posts;
        this.comments = comments;
        this.companies = companies;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.firebaseAdmin = firebaseAdmin;
        this.deactivatedRetentionDays = Math.max(1, deactivatedRetentionDays);
        this.usernameTombstoneDays = Math.max(1, usernameTombstoneDays);
        this.defaultCompanyDomain = defaultCompanyDomain == null ? "" : defaultCompanyDomain.trim().toLowerCase(Locale.ROOT);
    }

    public ProfileResult profile(String firebaseUid, long targetUserId) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ProfileResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return ProfileResult.notFound();
        if (!actor.get().companyId.equals(target.get().companyId)) return ProfileResult.forbidden();

        var verification = verifications.findByUserId(targetUserId).orElse(null);
        boolean includeFollowerCounts = target.get().showFollowerCount || actor.get().id == target.get().id;
        return ProfileResult.ok(buildProfile(target.get(), verification), includeFollowerCounts);
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

    public UpdateProfileResult updateProfile(String firebaseUid, String displayName, String bio, boolean isAnonymous, Boolean showFollowerCount) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return UpdateProfileResult.userNotProvisioned();
        users.updateProfile(actor.get().id, displayName, bio, isAnonymous, showFollowerCount);
        var updated = users.findById(actor.get().id).orElse(actor.get());
        var verification = verifications.findByUserId(actor.get().id).orElse(null);
        return UpdateProfileResult.ok(buildProfile(updated, verification));
    }

    public UpdateDisplayCommunityResult updateDisplayCommunity(String firebaseUid, Long communityId) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return UpdateDisplayCommunityResult.userNotProvisioned();

        if (communityId != null) {
            var community = communities.findById(communityId);
            if (community.isEmpty()) return UpdateDisplayCommunityResult.communityNotFound();
            boolean verified = communityVerifications.isVerified(actor.get().id, communityId);
            if (!verified) return UpdateDisplayCommunityResult.communityNotVerified();
        }

        users.updateDisplayCommunity(actor.get().id, communityId);
        var updated = users.findById(actor.get().id).orElse(actor.get());
        var verification = verifications.findByUserId(actor.get().id).orElse(null);
        return UpdateDisplayCommunityResult.ok(buildProfile(updated, verification));
    }

    public UpdateIdentityResult updateIdentity(String firebaseUid, String username,
                                               String firstName, String lastName, LocalDate dateOfBirth) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return UpdateIdentityResult.userNotProvisioned();

        String normalizedHandle = normalizeHandle(username);
        if (normalizedHandle == null) return UpdateIdentityResult.invalidUsername();

        String currentHandle = actor.get().handle == null ? "" : actor.get().handle.toLowerCase(Locale.ROOT);
        if (!normalizedHandle.equals(currentHandle) && !users.isHandleAvailable(normalizedHandle, handleReuseCutoff())) {
            return UpdateIdentityResult.usernameTaken();
        }

        try {
            users.updateIdentity(actor.get().id, normalizedHandle, firstName.trim(), lastName.trim(), dateOfBirth);
        } catch (DataAccessException e) {
            if (!normalizedHandle.equals(currentHandle) && !users.isHandleAvailable(normalizedHandle, handleReuseCutoff())) {
                return UpdateIdentityResult.usernameTaken();
            }
            return UpdateIdentityResult.conflict();
        }

        var updated = users.findById(actor.get().id).orElse(actor.get());
        var verification = verifications.findByUserId(actor.get().id).orElse(null);
        return UpdateIdentityResult.ok(buildProfile(updated, verification));
    }

    public OnboardResult onboard(String firebaseUid, String email, String username,
                                 String firstName, String lastName, LocalDate dateOfBirth) {
        if (firebaseUid == null || firebaseUid.isBlank()) return OnboardResult.badRequest("invalid_user");
        if (email == null || email.isBlank()) return OnboardResult.badRequest("email_required");
        if (users.isFirebaseUidTombstoned(firebaseUid)) return OnboardResult.conflict("account_deleted");
        var existing = users.findByFirebaseUidIncludingDeleted(firebaseUid);
        if (existing.isPresent()) return OnboardResult.conflict("already_onboarded");

        String normalizedHandle = normalizeHandle(username);
        if (normalizedHandle == null) return OnboardResult.badRequest("invalid_username");
        if (!users.isHandleAvailable(normalizedHandle, handleReuseCutoff())) return OnboardResult.conflict("username_taken");
        if (!users.isEmailAvailable(email)) return OnboardResult.conflict("email_taken");

        String emailDomain = extractDomain(email);
        if (emailDomain == null) return OnboardResult.badRequest("invalid_email");
        String companyDomain = normalizeDomain(defaultCompanyDomain);
        if (companyDomain == null) return OnboardResult.badRequest("company_not_configured");
        var company = companies.findByDomain(companyDomain);
        if (company.isEmpty()) return OnboardResult.badRequest("company_not_configured");

        long userId;
        try {
            userId = users.insert(firebaseUid, normalizedHandle, email, company.get().id,
                    firstName.trim(), lastName.trim(), dateOfBirth);
        } catch (DataAccessException e) {
            if (!users.isHandleAvailable(normalizedHandle, handleReuseCutoff())) return OnboardResult.conflict("username_taken");
            if (!users.isEmailAvailable(email)) return OnboardResult.conflict("email_taken");
            return OnboardResult.conflict("conflict");
        }
        var user = users.findById(userId).orElseThrow();
        var verification = verifications.findByUserId(userId).orElse(null);
        return OnboardResult.ok(buildProfile(user, verification));
    }

    public AvailabilityResult usernameAvailability(String username) {
        String normalizedHandle = normalizeHandle(username);
        if (normalizedHandle == null) return AvailabilityResult.invalid();
        boolean available = users.isHandleAvailable(normalizedHandle, handleReuseCutoff());
        return AvailabilityResult.ok(normalizedHandle, available);
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

    public void syncEmail(String firebaseUid, String email) {
        if (email == null || email.isBlank()) return;
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty()) return;
        if (user.get().email != null && user.get().email.equalsIgnoreCase(email)) return;
        if (!users.isEmailAvailableForUser(user.get().id, email)) return;
        users.updateEmail(user.get().id, email);
    }

    public LoginStatus onLogin(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) return LoginStatus.MISSING;
        var existing = users.findByFirebaseUidIncludingDeleted(firebaseUid);
        if (existing.isEmpty()) {
            return users.isFirebaseUidTombstoned(firebaseUid) ? LoginStatus.PURGED : LoginStatus.MISSING;
        }
        if (existing.get().deletedAt == null) return LoginStatus.ACTIVE;
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(deactivatedRetentionDays);
        if (existing.get().deletedAt.isBefore(cutoff)) {
            var firebaseResult = firebaseAdmin.deleteUser(firebaseUid);
            if (firebaseAdmin.isRequired()
                    && (firebaseResult.status() == FirebaseAdminService.DeleteStatus.FAILED
                    || firebaseResult.status() == FirebaseAdminService.DeleteStatus.SKIPPED)) {
                return LoginStatus.PURGE_FAILED;
            }
            var deleted = users.deleteByFirebaseUidIfDeletedBefore(firebaseUid, cutoff);
            deleted.ifPresent(users::insertTombstone);
            return deleted.isPresent() ? LoginStatus.PURGED : LoginStatus.MISSING;
        }
        users.reactivate(existing.get().id);
        return LoginStatus.REACTIVATED;
    }

    public int purgeDeactivated() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(deactivatedRetentionDays);
        int total = 0;
        while (true) {
            var candidates = users.listSoftDeletedBefore(cutoff, 200);
            if (candidates.isEmpty()) break;
            int deletedCount = 0;
            for (var row : candidates) {
                var firebaseResult = firebaseAdmin.deleteUser(row.firebaseUid);
                if (firebaseAdmin.isRequired()
                        && (firebaseResult.status() == FirebaseAdminService.DeleteStatus.FAILED
                        || firebaseResult.status() == FirebaseAdminService.DeleteStatus.SKIPPED)) {
                    continue;
                }
                var deleted = users.deleteById(row.id);
                deleted.ifPresent(users::insertTombstone);
                if (deleted.isPresent()) deletedCount += 1;
            }
            if (deletedCount == 0) {
                break;
            }
            total += deletedCount;
        }
        return total;
    }

    private Optional<UserRepository.UserRow> requireProvisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
    }

    private OffsetDateTime handleReuseCutoff() {
        return OffsetDateTime.now().minusDays(usernameTombstoneDays);
    }

    private UserProfile buildProfile(UserRepository.UserRow row, VerificationRepository.Row verification) {
        var verificationData = verification == null ? null : new Verification(verification.method, verification.verified, verification.verifiedAt);
        var displayCommunity = users.findDisplayCommunityForUser(row.id)
                .map(dc -> new DisplayCommunity(dc.id, dc.name, dc.kind, dc.specializationType))
                .orElse(null);
        var stats = new ProfileStats(
                users.countFollowers(row.id),
                users.countFollowing(row.id),
                users.countPosts(row.id),
                users.countComments(row.id)
        );
        return new UserProfile(
                row.id,
                row.handle,
                row.firstName,
                row.lastName,
                row.dateOfBirth,
                row.displayName,
                row.bio,
                row.isAnonymous,
                row.showFollowerCount,
                row.companyId,
                row.createdAt,
                row.profileImageUrl,
                verificationData,
                displayCommunity,
                stats
        );
    }

    private String normalizeHandle(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isBlank()) return null;
        if (!trimmed.matches("^[a-z0-9_]{3,30}$")) return null;
        return trimmed;
    }

    private String extractDomain(String email) {
        if (email == null) return null;
        String trimmed = email.trim().toLowerCase(Locale.ROOT);
        int at = trimmed.indexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) return null;
        return trimmed.substring(at + 1);
    }

    private String normalizeDomain(String domain) {
        if (domain == null) return null;
        String trimmed = domain.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isBlank()) return null;
        if (!trimmed.matches("^[a-z0-9.-]+$")) return null;
        return trimmed;
    }

    public enum Status { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN }

    public record ProfileResult(Status status, UserProfile profile, boolean includeFollowerCounts) {
        static ProfileResult ok(UserProfile profile, boolean includeFollowerCounts) {
            return new ProfileResult(Status.OK, profile, includeFollowerCounts);
        }
        static ProfileResult userNotProvisioned() { return new ProfileResult(Status.USER_NOT_PROVISIONED, null, false); }
        static ProfileResult notFound() { return new ProfileResult(Status.NOT_FOUND, null, false); }
        static ProfileResult forbidden() { return new ProfileResult(Status.FORBIDDEN, null, false); }
    }

    public record UpdateProfileResult(Status status, UserProfile profile) {
        static UpdateProfileResult ok(UserProfile profile) { return new UpdateProfileResult(Status.OK, profile); }
        static UpdateProfileResult userNotProvisioned() { return new UpdateProfileResult(Status.USER_NOT_PROVISIONED, null); }
    }

    public enum UpdateIdentityStatus { OK, USER_NOT_PROVISIONED, INVALID_USERNAME, USERNAME_TAKEN, CONFLICT }
    public record UpdateIdentityResult(UpdateIdentityStatus status, UserProfile profile) {
        static UpdateIdentityResult ok(UserProfile profile) { return new UpdateIdentityResult(UpdateIdentityStatus.OK, profile); }
        static UpdateIdentityResult userNotProvisioned() { return new UpdateIdentityResult(UpdateIdentityStatus.USER_NOT_PROVISIONED, null); }
        static UpdateIdentityResult invalidUsername() { return new UpdateIdentityResult(UpdateIdentityStatus.INVALID_USERNAME, null); }
        static UpdateIdentityResult usernameTaken() { return new UpdateIdentityResult(UpdateIdentityStatus.USERNAME_TAKEN, null); }
        static UpdateIdentityResult conflict() { return new UpdateIdentityResult(UpdateIdentityStatus.CONFLICT, null); }
    }

    public enum OnboardStatus { OK, CONFLICT, BAD_REQUEST }
    public record OnboardResult(OnboardStatus status, UserProfile profile, String error) {
        static OnboardResult ok(UserProfile profile) { return new OnboardResult(OnboardStatus.OK, profile, null); }
        static OnboardResult conflict(String error) { return new OnboardResult(OnboardStatus.CONFLICT, null, error); }
        static OnboardResult badRequest(String error) { return new OnboardResult(OnboardStatus.BAD_REQUEST, null, error); }
    }

    public record AvailabilityResult(boolean valid, String username, boolean available) {
        static AvailabilityResult ok(String username, boolean available) { return new AvailabilityResult(true, username, available); }
        static AvailabilityResult invalid() { return new AvailabilityResult(false, null, false); }
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

    public record UserProfile(long id, String handle, String firstName, String lastName, LocalDate dateOfBirth,
                              String displayName, String bio, boolean isAnonymous, boolean showFollowerCount, Long companyId,
                              OffsetDateTime createdAt, String profileImageUrl, Verification verification,
                              DisplayCommunity displayCommunity, ProfileStats stats) {}

    public record DisplayCommunity(long id, String name, String kind, String specializationType) {}

    public record ProfileStats(int followerCount, int followingCount, int postsCount, int commentsCount) {}

    public record Verification(String method, boolean verified, OffsetDateTime verifiedAt) {}

    public enum UpdateDisplayCommunityStatus { OK, USER_NOT_PROVISIONED, COMMUNITY_NOT_FOUND, COMMUNITY_NOT_VERIFIED }

    public record UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus status, UserProfile profile) {
        static UpdateDisplayCommunityResult ok(UserProfile profile) { return new UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus.OK, profile); }
        static UpdateDisplayCommunityResult userNotProvisioned() { return new UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus.USER_NOT_PROVISIONED, null); }
        static UpdateDisplayCommunityResult communityNotFound() { return new UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus.COMMUNITY_NOT_FOUND, null); }
        static UpdateDisplayCommunityResult communityNotVerified() { return new UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus.COMMUNITY_NOT_VERIFIED, null); }
    }

    public record PostsResult(Status status, List<PostRepository.PostRow> posts, String nextCursor) {
        static PostsResult ok(List<PostRepository.PostRow> posts, String next) { return new PostsResult(Status.OK, posts, next); }
        static PostsResult userNotProvisioned() { return new PostsResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static PostsResult notFound() { return new PostsResult(Status.NOT_FOUND, List.of(), null); }
        static PostsResult forbidden() { return new PostsResult(Status.FORBIDDEN, List.of(), null); }
    }

    public DeleteResult deleteMe(String firebaseUid, DeleteMode mode) {
        var userOpt = users.findByFirebaseUidIncludingDeleted(firebaseUid);
        if (userOpt.isEmpty()) {
            if (mode == DeleteMode.SOFT) {
                return DeleteResult.ok(FirebaseDeleteStatus.NOT_REQUESTED, null);
            }
            var firebaseResult = firebaseAdmin.deleteUser(firebaseUid);
            return handleFirebaseOnlyDelete(firebaseResult);
        }
        var user = userOpt.get();
        if (mode == DeleteMode.SOFT) {
            if (user.deletedAt != null) return DeleteResult.ok(FirebaseDeleteStatus.NOT_REQUESTED, null);
            users.softDelete(user.id, user.id);
            return DeleteResult.ok(FirebaseDeleteStatus.NOT_REQUESTED, null);
        }
        var firebaseResult = firebaseAdmin.deleteUser(firebaseUid);
        var firebaseHandled = handleFirebaseResult(firebaseResult);
        if (firebaseHandled.status() != DeleteStatus.OK) {
            return firebaseHandled;
        }
        var deleted = users.deleteById(user.id);
        deleted.ifPresent(users::insertTombstone);
        return DeleteResult.ok(firebaseHandled.firebaseStatus, firebaseHandled.error);
    }

    public enum DeleteMode { HARD, SOFT }

    public enum DeleteStatus { OK, FIREBASE_DELETE_FAILED, FIREBASE_DELETE_SKIPPED }

    public enum FirebaseDeleteStatus { OK, SKIPPED, FAILED, NOT_REQUESTED }

    public record DeleteResult(DeleteStatus status, FirebaseDeleteStatus firebaseStatus, String error) {
        static DeleteResult ok(FirebaseDeleteStatus firebaseStatus, String error) {
            return new DeleteResult(DeleteStatus.OK, firebaseStatus, error);
        }
        static DeleteResult firebaseDeleteFailed(String error) {
            return new DeleteResult(DeleteStatus.FIREBASE_DELETE_FAILED, FirebaseDeleteStatus.FAILED, error);
        }
        static DeleteResult firebaseDeleteSkipped(String error) {
            return new DeleteResult(DeleteStatus.FIREBASE_DELETE_SKIPPED, FirebaseDeleteStatus.SKIPPED, error);
        }
    }

    public enum LoginStatus { ACTIVE, REACTIVATED, PURGED, MISSING, PURGE_FAILED }

    private DeleteResult handleFirebaseResult(FirebaseAdminService.DeleteResult firebaseResult) {
        return handleFirebaseResult(firebaseResult, false);
    }

    private DeleteResult handleFirebaseOnlyDelete(FirebaseAdminService.DeleteResult firebaseResult) {
        return handleFirebaseResult(firebaseResult, true);
    }

    private DeleteResult handleFirebaseResult(FirebaseAdminService.DeleteResult firebaseResult, boolean firebaseOnly) {
        if (firebaseResult.status() == FirebaseAdminService.DeleteStatus.OK) {
            return DeleteResult.ok(FirebaseDeleteStatus.OK, null);
        }
        if (firebaseResult.status() == FirebaseAdminService.DeleteStatus.SKIPPED) {
            if (firebaseAdmin.isRequired()) {
                return DeleteResult.firebaseDeleteSkipped(firebaseResult.error());
            }
            return DeleteResult.ok(FirebaseDeleteStatus.SKIPPED, firebaseResult.error());
        }
        if (firebaseResult.status() == FirebaseAdminService.DeleteStatus.FAILED) {
            if (firebaseAdmin.isRequired()) {
                return DeleteResult.firebaseDeleteFailed(firebaseResult.error());
            }
            return DeleteResult.ok(FirebaseDeleteStatus.FAILED, firebaseResult.error());
        }
        return firebaseOnly
                ? DeleteResult.ok(FirebaseDeleteStatus.NOT_REQUESTED, null)
                : DeleteResult.ok(FirebaseDeleteStatus.NOT_REQUESTED, null);
    }
}
