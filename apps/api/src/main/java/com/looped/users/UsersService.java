package com.looped.users;

import com.looped.auth.FirebaseAdminService;
import com.looped.comments.CommentsRepository;
import com.looped.companies.CompanyRepository;
import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.SpecializationJoinsRepository;
import com.looped.media.MediaRepository;
import com.looped.posts.PostRepository;
import com.looped.posts.PostStateService;
import com.looped.principals.PrincipalRepository;
import com.looped.shared.Pagination;
import com.looped.shared.RankPagination;
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
    private final PrincipalRepository principals;
    private final PostStateService postState;
    private final CommentsRepository comments;
    private final UserContentRepository content;
    private final CompanyRepository companies;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final SpecializationJoinsRepository specializationJoins;
    private final MediaRepository media;
    private final FirebaseAdminService firebaseAdmin;
    private final int deactivatedRetentionDays;
    private final int usernameTombstoneDays;
    private final String defaultCompanyDomain;
    private final String cloudfrontDomain;

    public UsersService(UserRepository users,
                        VerificationRepository verifications,
                        PostRepository posts,
                        PrincipalRepository principals,
                        PostStateService postState,
                        CommentsRepository comments,
                        UserContentRepository content,
                        CompanyRepository companies,
                        CommunitiesRepository communities,
                        CommunityVerificationsRepository communityVerifications,
                        SpecializationJoinsRepository specializationJoins,
                        MediaRepository media,
                        FirebaseAdminService firebaseAdmin,
                        @Value("${retention.deactivated-days:90}") int deactivatedRetentionDays,
                        @Value("${retention.username-tombstone-days:14}") int usernameTombstoneDays,
                        @Value("${onboarding.default-company-domain:looped.global}") String defaultCompanyDomain,
                        @Value("${cloudfront.domain:}") String cloudfrontDomain) {
        this.users = users;
        this.verifications = verifications;
        this.posts = posts;
        this.principals = principals;
        this.postState = postState;
        this.comments = comments;
        this.content = content;
        this.companies = companies;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.specializationJoins = specializationJoins;
        this.media = media;
        this.firebaseAdmin = firebaseAdmin;
        this.deactivatedRetentionDays = Math.max(1, deactivatedRetentionDays);
        this.usernameTombstoneDays = Math.max(1, usernameTombstoneDays);
        this.defaultCompanyDomain = defaultCompanyDomain == null ? "" : defaultCompanyDomain.trim().toLowerCase(Locale.ROOT);
        this.cloudfrontDomain = cloudfrontDomain == null ? "" : cloudfrontDomain.trim();
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

        var rows = posts.findByAuthor(targetUserId, cTs, cId, limit, actor.get().id, actor.get().hideAnonymousPosts);
        var principal = principals.createForUser(actor.get().id);
        postState.applyForPrincipal(principal.id, rows);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return PostsResult.ok(rows, next);
    }

    public ContentResult content(String firebaseUid, long targetUserId, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ContentResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return ContentResult.notFound();
        if (!actor.get().companyId.equals(target.get().companyId)) return ContentResult.forbidden();
        return contentImpl(actor.get(), targetUserId, cursor, limit);
    }

    public ContentResult contentMe(String firebaseUid, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ContentResult.userNotProvisioned();
        return contentImpl(actor.get(), actor.get().id, cursor, limit);
    }

    private ContentResult contentImpl(UserRepository.UserRow actor, long targetUserId, String cursor, int limit) {
        OffsetDateTime cTs = null;
        Long cSortId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cSortId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }

        var refs = content.list(targetUserId, actor.id, actor.hideAnonymousPosts, cTs, cSortId, limit);
        if (refs.isEmpty()) return ContentResult.ok(java.util.List.of(), null);

        java.util.List<Long> postIds = refs.stream()
                .filter(r -> "post".equals(r.type()))
                .map(UserContentRepository.ContentRefRow::entityId)
                .toList();
        java.util.List<Long> replyIds = refs.stream()
                .filter(r -> "reply".equals(r.type()))
                .map(UserContentRepository.ContentRefRow::entityId)
                .toList();

        java.util.Map<Long, PostRepository.PostRow> postsById = new java.util.HashMap<>();
        if (!postIds.isEmpty()) {
            var postRows = posts.findByIds(postIds);
            var viewerPrincipal = principals.createForUser(actor.id);
            postState.applyForPrincipal(viewerPrincipal.id, postRows);
            for (var p : postRows) postsById.put(p.id, p);
        }

        java.util.Map<Long, CommentsRepository.CommentRow> repliesById = new java.util.HashMap<>();
        if (!replyIds.isEmpty()) {
            for (var row : comments.findByIds(replyIds)) repliesById.put(row.id, row);
        }

        java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
        for (var ref : refs) {
            if ("post".equals(ref.type())) {
                var p = postsById.get(ref.entityId());
                if (p == null) continue;
                items.add(java.util.Map.of(
                        "type", "post",
                        "created_at", ref.createdAt(),
                        "post", com.looped.posts.PostPayloads.from(p)
                ));
            } else if ("reply".equals(ref.type())) {
                var c = repliesById.get(ref.entityId());
                if (c == null) continue;
                items.add(java.util.Map.of(
                        "type", "reply",
                        "created_at", ref.createdAt(),
                        "reply", UserPayloads.comment(c)
                ));
            }
        }

        String next = null;
        if (refs.size() == limit) {
            var last = refs.get(refs.size() - 1);
            next = Pagination.encode(last.createdAt(), last.sortId());
        }
        return ContentResult.ok(items, next);
    }

    public UpdateProfileResult updateProfile(String firebaseUid, String displayName, String bio, boolean isAnonymous,
                                             Boolean showFollowerCount, String messagePermission, Long profileMediaAssetId) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return UpdateProfileResult.userNotProvisioned();
        String normalizedPermission = normalizeMessagePermission(messagePermission);
        if (messagePermission != null && normalizedPermission == null) {
            return UpdateProfileResult.invalidMessagePermission();
        }

        String profileImageUrl = null;
        if (profileMediaAssetId != null) {
            if (cloudfrontDomain.isBlank()) {
                return UpdateProfileResult.cdnNotConfigured();
            }
            var mediaRow = media.findById(profileMediaAssetId);
            if (mediaRow.isEmpty()) {
                return UpdateProfileResult.mediaAssetNotFound();
            }
            var asset = mediaRow.get();
            if (asset.ownerId == null || asset.ownerId.longValue() != actor.get().id) {
                return UpdateProfileResult.mediaAssetForbidden();
            }
            String mime = asset.mimeType == null ? "" : asset.mimeType.trim().toLowerCase(Locale.ROOT);
            if (!mime.equals("image/jpeg") && !mime.equals("image/png") && !mime.equals("image/webp")) {
                return UpdateProfileResult.invalidProfileImage();
            }
            profileImageUrl = "https://" + cloudfrontDomain + "/" + asset.s3Key;
        }

        users.updateProfile(actor.get().id, displayName, bio, isAnonymous, showFollowerCount, normalizedPermission, profileImageUrl);
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

    public UpdateDisplaySpecializationResult updateDisplaySpecialization(String firebaseUid, Long specializationId) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return UpdateDisplaySpecializationResult.userNotProvisioned();

        if (specializationId != null) {
            var community = communities.findById(specializationId);
            if (community.isEmpty()) return UpdateDisplaySpecializationResult.specializationNotFound();
            var row = community.get();
            if (!"specialization".equalsIgnoreCase(row.kind)) {
                return UpdateDisplaySpecializationResult.invalidSpecialization();
            }
            String specializationType = normalizeSpecializationType(row.specializationType);
            if (specializationType == null) {
                return UpdateDisplaySpecializationResult.invalidSpecialization();
            }
            if (!specializationJoins.exists(actor.get().id, specializationId)) {
                return UpdateDisplaySpecializationResult.specializationNotJoined();
            }
        }

        users.updateDisplaySpecialization(actor.get().id, specializationId);
        var updated = users.findById(actor.get().id).orElse(actor.get());
        var verification = verifications.findByUserId(actor.get().id).orElse(null);
        return UpdateDisplaySpecializationResult.ok(buildProfile(updated, verification));
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
        return usernameAvailability(null, username);
    }

    public AvailabilityResult usernameAvailability(String firebaseUid, String username) {
        String normalizedHandle = normalizeHandle(username);
        if (normalizedHandle == null) return AvailabilityResult.invalid();

        if (firebaseUid != null && !firebaseUid.isBlank()) {
            var me = users.findByFirebaseUid(firebaseUid);
            if (me.isPresent() && me.get().handle != null
                    && normalizedHandle.equals(me.get().handle.toLowerCase(Locale.ROOT))) {
                return AvailabilityResult.ownedByMe(normalizedHandle);
            }
        }
        boolean available = users.isHandleAvailable(normalizedHandle, handleReuseCutoff());
        return AvailabilityResult.ok(normalizedHandle, available, false);
    }

    public SearchResult search(String firebaseUid, String query, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return SearchResult.userNotProvisioned();

        RankPagination.Cursor rankedCursor = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                rankedCursor = RankPagination.decode(cursor);
            } catch (IllegalArgumentException ignored) {}
        }
        OffsetDateTime asOf = rankedCursor == null ? OffsetDateTime.now() : rankedCursor.asOf();
        Long score = rankedCursor == null ? null : rankedCursor.score();
        OffsetDateTime cTs = rankedCursor == null ? null : rankedCursor.timestamp();
        Long cId = rankedCursor == null ? null : rankedCursor.id();

        String prefixQuery = UsersSearchQuery.toPrefixTsquery(query);
        var scoredRows = users.searchCompanyUsersRanked(
                actor.get().companyId,
                query,
                prefixQuery,
                asOf,
                score,
                cTs,
                cId,
                limit
        );
        var rows = scoredRows.stream().map(r -> r.user).toList();
        String next = null;
        if (scoredRows.size() == limit) {
            var last = scoredRows.get(scoredRows.size() - 1);
            next = RankPagination.encode(asOf, last.score, last.user.createdAt, last.user.id);
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

    private static final String ONBOARDING_STEP_PROFILE_SETUP = "profile_setup";
    private static final String ONBOARDING_STEP_SELECT_COMPANY = "select_company";
    private static final String ONBOARDING_STEP_VERIFICATION = "verification";
    private static final String ONBOARDING_STEP_VERIFICATION_NOTIFICATIONS = "verification_notifications";

    public OnboardingState onboardingState(String firebaseUid) {
        var userOpt = users.findByFirebaseUid(firebaseUid);
        if (userOpt.isEmpty()) {
            return new OnboardingState(false, ONBOARDING_STEP_PROFILE_SETUP);
        }
        var user = userOpt.get();
        if (user.companyId == null) {
            return new OnboardingState(false, ONBOARDING_STEP_SELECT_COMPANY);
        }
        if (user.onboardingCompletedAt != null) {
            return new OnboardingState(true, ONBOARDING_STEP_VERIFICATION_NOTIFICATIONS);
        }
        String step = user.onboardingStep;
        if (step == null || step.isBlank()) step = ONBOARDING_STEP_VERIFICATION;
        return new OnboardingState(false, step);
    }

    public UpdateOnboardingResult updateOnboardingStep(String firebaseUid, String step) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return UpdateOnboardingResult.userNotProvisioned();

        if (actor.get().onboardingCompletedAt != null) {
            return UpdateOnboardingResult.ok(new OnboardingState(true, ONBOARDING_STEP_VERIFICATION_NOTIFICATIONS));
        }

        String normalized = step == null ? null : step.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.isBlank()) return UpdateOnboardingResult.invalidStep();

        return switch (normalized) {
            case ONBOARDING_STEP_VERIFICATION -> {
                users.updateOnboardingStep(actor.get().id, ONBOARDING_STEP_VERIFICATION);
                yield UpdateOnboardingResult.ok(new OnboardingState(false, ONBOARDING_STEP_VERIFICATION));
            }
            case ONBOARDING_STEP_VERIFICATION_NOTIFICATIONS -> {
                users.markOnboardingComplete(actor.get().id);
                yield UpdateOnboardingResult.ok(new OnboardingState(true, ONBOARDING_STEP_VERIFICATION_NOTIFICATIONS));
            }
            default -> UpdateOnboardingResult.invalidStep();
        };
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
        var displaySpecialization = users.findDisplaySpecializationForUser(row.id)
                .map(ds -> new DisplaySpecialization(ds.id, ds.name, ds.kind, ds.specializationType))
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
                row.messagePermission,
                row.hideAnonymousPosts,
                row.companyId,
                row.createdAt,
                row.profileImageUrl,
                verificationData,
                displayCommunity,
                displaySpecialization,
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

    private String normalizeSpecializationType(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (!normalized.equals("major") && !normalized.equals("department")) return null;
        return normalized;
    }

    private String normalizeMessagePermission(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (!normalized.equals("company") && !normalized.equals("following") && !normalized.equals("no_one")
                && !normalized.equals("all")) {
            return null;
        }
        return normalized;
    }

    public enum Status {
        OK,
        USER_NOT_PROVISIONED,
        NOT_FOUND,
        FORBIDDEN,
        INVALID_MESSAGE_PERMISSION,
        MEDIA_ASSET_NOT_FOUND,
        MEDIA_ASSET_FORBIDDEN,
        INVALID_PROFILE_IMAGE,
        CDN_NOT_CONFIGURED
    }

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
        static UpdateProfileResult invalidMessagePermission() { return new UpdateProfileResult(Status.INVALID_MESSAGE_PERMISSION, null); }
        static UpdateProfileResult mediaAssetNotFound() { return new UpdateProfileResult(Status.MEDIA_ASSET_NOT_FOUND, null); }
        static UpdateProfileResult mediaAssetForbidden() { return new UpdateProfileResult(Status.MEDIA_ASSET_FORBIDDEN, null); }
        static UpdateProfileResult invalidProfileImage() { return new UpdateProfileResult(Status.INVALID_PROFILE_IMAGE, null); }
        static UpdateProfileResult cdnNotConfigured() { return new UpdateProfileResult(Status.CDN_NOT_CONFIGURED, null); }
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

    public record AvailabilityResult(boolean valid, String username, boolean available, boolean ownedByMe) {
        static AvailabilityResult ok(String username, boolean available, boolean ownedByMe) {
            return new AvailabilityResult(true, username, available, ownedByMe);
        }

        static AvailabilityResult ownedByMe(String username) {
            return new AvailabilityResult(true, username, true, true);
        }

        static AvailabilityResult invalid() { return new AvailabilityResult(false, null, false, false); }
    }

    public record OnboardingState(boolean onboardingComplete, String onboardingStep) {}

    public enum UpdateOnboardingStatus { OK, USER_NOT_PROVISIONED, INVALID_STEP }

    public record UpdateOnboardingResult(UpdateOnboardingStatus status, OnboardingState state) {
        static UpdateOnboardingResult ok(OnboardingState state) { return new UpdateOnboardingResult(UpdateOnboardingStatus.OK, state); }
        static UpdateOnboardingResult userNotProvisioned() { return new UpdateOnboardingResult(UpdateOnboardingStatus.USER_NOT_PROVISIONED, null); }
        static UpdateOnboardingResult invalidStep() { return new UpdateOnboardingResult(UpdateOnboardingStatus.INVALID_STEP, null); }
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

    public record ContentResult(Status status, List<java.util.Map<String, Object>> items, String nextCursor) {
        static ContentResult ok(List<java.util.Map<String, Object>> items, String next) { return new ContentResult(Status.OK, items, next); }
        static ContentResult userNotProvisioned() { return new ContentResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static ContentResult notFound() { return new ContentResult(Status.NOT_FOUND, List.of(), null); }
        static ContentResult forbidden() { return new ContentResult(Status.FORBIDDEN, List.of(), null); }
    }

    public record UserProfile(long id, String handle, String firstName, String lastName, LocalDate dateOfBirth,
                              String displayName, String bio, boolean isAnonymous, boolean showFollowerCount,
                              String messagePermission, boolean hideAnonymousPosts, Long companyId, OffsetDateTime createdAt, String profileImageUrl,
                              Verification verification, DisplayCommunity displayCommunity,
                              DisplaySpecialization displaySpecialization, ProfileStats stats) {}

    public record DisplayCommunity(long id, String name, String kind, String specializationType) {}

    public record DisplaySpecialization(long id, String name, String kind, String specializationType) {}

    public record ProfileStats(int followerCount, int followingCount, int postsCount, int commentsCount) {}

    public record Verification(String method, boolean verified, OffsetDateTime verifiedAt) {}

    public enum UpdateDisplayCommunityStatus { OK, USER_NOT_PROVISIONED, COMMUNITY_NOT_FOUND, COMMUNITY_NOT_VERIFIED }

    public record UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus status, UserProfile profile) {
        static UpdateDisplayCommunityResult ok(UserProfile profile) { return new UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus.OK, profile); }
        static UpdateDisplayCommunityResult userNotProvisioned() { return new UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus.USER_NOT_PROVISIONED, null); }
        static UpdateDisplayCommunityResult communityNotFound() { return new UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus.COMMUNITY_NOT_FOUND, null); }
        static UpdateDisplayCommunityResult communityNotVerified() { return new UpdateDisplayCommunityResult(UpdateDisplayCommunityStatus.COMMUNITY_NOT_VERIFIED, null); }
    }

    public enum UpdateDisplaySpecializationStatus {
        OK, USER_NOT_PROVISIONED, SPECIALIZATION_NOT_FOUND, INVALID_SPECIALIZATION, SPECIALIZATION_NOT_JOINED
    }

    public record UpdateDisplaySpecializationResult(UpdateDisplaySpecializationStatus status, UserProfile profile) {
        static UpdateDisplaySpecializationResult ok(UserProfile profile) {
            return new UpdateDisplaySpecializationResult(UpdateDisplaySpecializationStatus.OK, profile);
        }
        static UpdateDisplaySpecializationResult userNotProvisioned() {
            return new UpdateDisplaySpecializationResult(UpdateDisplaySpecializationStatus.USER_NOT_PROVISIONED, null);
        }
        static UpdateDisplaySpecializationResult specializationNotFound() {
            return new UpdateDisplaySpecializationResult(UpdateDisplaySpecializationStatus.SPECIALIZATION_NOT_FOUND, null);
        }
        static UpdateDisplaySpecializationResult invalidSpecialization() {
            return new UpdateDisplaySpecializationResult(UpdateDisplaySpecializationStatus.INVALID_SPECIALIZATION, null);
        }
        static UpdateDisplaySpecializationResult specializationNotJoined() {
            return new UpdateDisplaySpecializationResult(UpdateDisplaySpecializationStatus.SPECIALIZATION_NOT_JOINED, null);
        }
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
