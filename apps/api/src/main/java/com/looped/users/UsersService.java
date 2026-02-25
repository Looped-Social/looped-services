package com.looped.users;

import com.looped.auth.FirebaseAdminService;
import com.looped.comments.CommentsRepository;
import com.looped.companies.CompanyRepository;
import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.SpecializationJoinsRepository;
import com.looped.media.MediaRepository;
import com.looped.polls.PollPayloads;
import com.looped.polls.PollsService;
import com.looped.posts.PostRepository;
import com.looped.posts.PostStateService;
import com.looped.posts.PostViewerCapabilitiesService;
import com.looped.principals.PrincipalRepository;
import com.looped.settings.AppConfigService;
import com.looped.shared.Pagination;
import com.looped.shared.RankPagination;
import com.looped.verification.VerificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class UsersService {
    private static final Logger log = LoggerFactory.getLogger(UsersService.class);
    private final UserRepository users;
    private final UserDeletionOperationRepository deletionOperations;
    private final VerificationRepository verifications;
    private final PostRepository posts;
    private final PrincipalRepository principals;
    private final BlocksRepository blocks;
    private final PostStateService postState;
    private final PostViewerCapabilitiesService viewerCapabilities;
    private final PollsService pollsService;
    private final CommentsRepository comments;
    private final UserContentRepository content;
    private final CompanyRepository companies;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final SpecializationJoinsRepository specializationJoins;
    private final MediaRepository media;
    private final OnboardingV2Service onboardingV2;
    private final FirebaseAdminService firebaseAdmin;
    private final AppConfigService appConfig;
    private final int deactivatedRetentionDays;
    private final int usernameTombstoneDays;
    private final String defaultCompanyDomain;
    private final String cloudfrontDomain;

    public UsersService(UserRepository users,
                        UserDeletionOperationRepository deletionOperations,
                        VerificationRepository verifications,
                        PostRepository posts,
                        PrincipalRepository principals,
                        BlocksRepository blocks,
                        PostStateService postState,
                        PostViewerCapabilitiesService viewerCapabilities,
                        PollsService pollsService,
                        CommentsRepository comments,
                        UserContentRepository content,
                        CompanyRepository companies,
                        CommunitiesRepository communities,
                        CommunityVerificationsRepository communityVerifications,
                        SpecializationJoinsRepository specializationJoins,
                        MediaRepository media,
                        OnboardingV2Service onboardingV2,
                        FirebaseAdminService firebaseAdmin,
                        AppConfigService appConfig,
                        @Value("${retention.deactivated-days:90}") int deactivatedRetentionDays,
                        @Value("${retention.username-tombstone-days:14}") int usernameTombstoneDays,
                        @Value("${onboarding.default-company-domain:looped.global}") String defaultCompanyDomain,
                        @Value("${cloudfront.domain:}") String cloudfrontDomain) {
        this.users = users;
        this.deletionOperations = deletionOperations;
        this.verifications = verifications;
        this.posts = posts;
        this.principals = principals;
        this.blocks = blocks;
        this.postState = postState;
        this.viewerCapabilities = viewerCapabilities;
        this.pollsService = pollsService;
        this.comments = comments;
        this.content = content;
        this.companies = companies;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.specializationJoins = specializationJoins;
        this.media = media;
        this.onboardingV2 = onboardingV2;
        this.firebaseAdmin = firebaseAdmin;
        this.appConfig = appConfig;
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
        long viewerPrincipalId = principals.createForUser(actor.get().id).id;
        long targetPrincipalId = principals.createForUser(target.get().id).id;
        boolean viewerHasBlocked = blocks.exists(viewerPrincipalId, targetPrincipalId);
        boolean viewerBlockedBy = blocks.exists(targetPrincipalId, viewerPrincipalId);
        return ProfileResult.ok(buildProfile(target.get(), verification), includeFollowerCounts, viewerHasBlocked, viewerBlockedBy);
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
        return content(firebaseUid, targetUserId, cursor, limit, false);
    }

    public ContentResult content(String firebaseUid, long targetUserId, String cursor, int limit, boolean includePostPreview) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ContentResult.userNotProvisioned();

        var target = users.findById(targetUserId);
        if (target.isEmpty()) return ContentResult.notFound();
        if (!actor.get().companyId.equals(target.get().companyId)) return ContentResult.forbidden();
        return contentImpl(actor.get(), targetUserId, cursor, limit, includePostPreview);
    }

    public ContentResult contentMe(String firebaseUid, String cursor, int limit) {
        return contentMe(firebaseUid, cursor, limit, false);
    }

    public ContentResult contentMe(String firebaseUid, String cursor, int limit, boolean includePostPreview) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ContentResult.userNotProvisioned();
        return contentImpl(actor.get(), actor.get().id, cursor, limit, includePostPreview);
    }

    private ContentResult contentImpl(UserRepository.UserRow actor, long targetUserId, String cursor, int limit, boolean includePostPreview) {
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

        var viewerPrincipal = principals.createForUser(actor.id);

        java.util.Map<Long, PostRepository.PostRow> postsById = new java.util.HashMap<>();
        if (!postIds.isEmpty()) {
            var postRows = posts.findByIds(postIds);
            postState.applyForPrincipal(viewerPrincipal.id, postRows);
            for (var p : postRows) postsById.put(p.id, p);
        }

        java.util.Map<Long, CommentsRepository.CommentRow> repliesById = new java.util.HashMap<>();
        if (!replyIds.isEmpty()) {
            for (var row : comments.findByIds(replyIds)) repliesById.put(row.id, row);
        }

        java.util.Map<Long, PostRepository.PostRow> replyPostsById = java.util.Map.of();
        if (includePostPreview && !repliesById.isEmpty()) {
            java.util.Set<Long> replyPostIds = new java.util.HashSet<>();
            for (var c : repliesById.values()) replyPostIds.add(c.postId);
            replyPostIds.removeAll(postsById.keySet());
            if (!replyPostIds.isEmpty()) {
                var rows = posts.findByIds(replyPostIds.stream().toList());
                postState.applyForPrincipal(viewerPrincipal.id, rows);
                java.util.Map<Long, PostRepository.PostRow> tmp = new java.util.HashMap<>();
                for (var p : rows) tmp.put(p.id, p);
                replyPostsById = tmp;
            }
        }

        java.util.Map<Long, PostRepository.PostRow> allPosts = new java.util.HashMap<>(postsById);
        allPosts.putAll(replyPostsById);
        java.util.List<Long> allPostIds = new java.util.ArrayList<>(allPosts.keySet());
        java.util.Map<Long, PollsService.PollView> pollsByPostId = allPostIds.isEmpty()
                ? java.util.Map.of()
                : pollsService.viewsByPostId(viewerPrincipal.id, allPostIds);

        java.util.Map<Long, java.util.Map<String, Object>> capabilitiesByPostId = java.util.Map.of();
        if (!allPosts.isEmpty()) {
            capabilitiesByPostId = viewerCapabilities.byPostId(
                    actor.id,
                    actor.companyId,
                    new java.util.ArrayList<>(allPosts.values()),
                    pollsByPostId
            );
        }

        String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
        java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
        for (var ref : refs) {
            if ("post".equals(ref.type())) {
                var p = postsById.get(ref.entityId());
                if (p == null) continue;
                items.add(java.util.Map.of(
                        "type", "post",
                        "created_at", ref.createdAt(),
                        "post", withPollAndCapabilities(
                                com.looped.posts.PostPayloads.from(p, defaultProfileImageUrl),
                                p.id,
                                pollsByPostId,
                                capabilitiesByPostId
                        )
                ));
            } else if ("reply".equals(ref.type())) {
                var c = repliesById.get(ref.entityId());
                if (c == null) continue;
                var payload = new java.util.HashMap<String, Object>();
                payload.put("type", "reply");
                payload.put("created_at", ref.createdAt());
                payload.put("reply", UserPayloads.comment(c));
                if (includePostPreview) {
                    var host = postsById.get(c.postId);
                    if (host == null) host = replyPostsById.get(c.postId);
                    if (host != null) {
                        payload.put("post", withPollAndCapabilities(
                                com.looped.posts.PostPayloads.from(host, defaultProfileImageUrl),
                                host.id,
                                pollsByPostId,
                                capabilitiesByPostId
                        ));
                    }
                }
                items.add(payload);
            }
        }

        String next = null;
        if (refs.size() == limit) {
            var last = refs.get(refs.size() - 1);
            next = Pagination.encode(last.createdAt(), last.sortId());
        }
        return ContentResult.ok(items, next);
    }

    private java.util.Map<String, Object> withPollAndCapabilities(
            java.util.Map<String, Object> postPayload,
            long postId,
            java.util.Map<Long, PollsService.PollView> pollsByPostId,
            java.util.Map<Long, java.util.Map<String, Object>> capabilitiesByPostId
    ) {
        var poll = pollsByPostId.get(postId);
        if (poll != null) postPayload.put("poll", PollPayloads.from(poll));
        com.looped.posts.PostPayloads.putViewerCapabilities(postPayload, capabilitiesByPostId.get(postId));
        return postPayload;
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
        users.markProfileCompletionCompletedIfEligible(actor.get().id);
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
        users.markProfileCompletionCompletedIfEligible(actor.get().id);
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
        return onboard(firebaseUid, email, null, username, firstName, lastName, dateOfBirth);
    }

    public OnboardResult onboard(String firebaseUid, String email, Boolean emailVerified, String username,
                                 String firstName, String lastName, LocalDate dateOfBirth) {
        if (firebaseUid == null || firebaseUid.isBlank()) return OnboardResult.badRequest("invalid_user");
        if (email == null || email.isBlank()) return OnboardResult.badRequest("email_required");
        if (users.isFirebaseUidTombstoned(firebaseUid)) return OnboardResult.conflict("account_deleted");
        if (deletionOperations.existsActiveByFirebaseUidOrEmail(firebaseUid, email)) {
            return OnboardResult.conflict("account_delete_pending");
        }
        var existing = users.findByFirebaseUidIncludingDeleted(firebaseUid);
        if (existing.isPresent()) return OnboardResult.conflict("already_onboarded");

        var claimed = claimActiveAccountByEmail(firebaseUid, email, emailVerified);
        if (claimed.isPresent()) {
            var verification = verifications.findByUserId(claimed.get().id).orElse(null);
            return OnboardResult.ok(buildProfile(claimed.get(), verification));
        }

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
        java.util.List<UserRepository.ScoredUserRow> scoredRows;
        try {
            scoredRows = users.searchCompanyUsersRankedV2(
                    actor.get().companyId,
                    actor.get().id,
                    query,
                    prefixQuery,
                    asOf,
                    score,
                    cTs,
                    cId,
                    limit
            );
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            // If pg_trgm isn't available in the DB (or migration hasn't run yet), fall back to v1 ranking.
            scoredRows = users.searchCompanyUsersRanked(
                    actor.get().companyId,
                    query,
                    prefixQuery,
                    asOf,
                    score,
                    cTs,
                    cId,
                    limit
            );
        }
        var rawRows = scoredRows.stream().map(r -> r.user).toList();
        var rows = excludeBlockedUsers(actor.get().id, rawRows);
        String next = null;
        if (scoredRows.size() == limit && !rawRows.isEmpty()) {
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
        var rawRows = users.listCompanyUsers(actor.get().companyId, cTs, cId, limit);
        var rows = excludeBlockedUsers(actor.get().id, rawRows);
        String next = null;
        if (rawRows.size() == limit && !rawRows.isEmpty()) {
            var last = rawRows.get(rawRows.size() - 1);
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

    public Optional<ProfileCompletionStatus> profileCompletionStatus(String firebaseUid) {
        var user = requireProvisionedUser(firebaseUid);
        if (user.isEmpty()) return Optional.empty();
        var verification = verifications.findByUserId(user.get().id).orElse(null);
        var profile = buildProfile(user.get(), verification);
        boolean onboardingComplete = user.get().onboardingCompletedAt != null;
        return Optional.of(profileCompletionStatus(onboardingComplete, profile));
    }

    public DismissProfileCompletionResult dismissProfileCompletionPrompt(String firebaseUid) {
        var user = requireProvisionedUser(firebaseUid);
        if (user.isEmpty()) return DismissProfileCompletionResult.userNotProvisioned();
        users.markProfileCompletionDismissed(user.get().id);
        var updated = users.findById(user.get().id).orElse(user.get());
        var verification = verifications.findByUserId(user.get().id).orElse(null);
        var profile = buildProfile(updated, verification);
        boolean onboardingComplete = updated.onboardingCompletedAt != null;
        return DismissProfileCompletionResult.ok(profileCompletionStatus(onboardingComplete, profile));
    }

    public ProfileCompletionStatus profileCompletionStatus(boolean onboardingComplete, UserProfile profile) {
        boolean missingPhoto = profile.profileImageUrl() == null || profile.profileImageUrl().isBlank();
        boolean missingBio = profile.bio() == null || profile.bio().trim().isEmpty();
        boolean missingSpecialization = profile.displaySpecialization() == null;
        boolean dismissed = profile.profileCompletionDismissedAt() != null;
        boolean shouldPrompt = onboardingComplete && (missingPhoto || missingBio || missingSpecialization) && !dismissed;
        return new ProfileCompletionStatus(
                profile.profileCompletionDismissedAt(),
                profile.profileCompletionCompletedAt(),
                missingPhoto,
                missingBio,
                missingSpecialization,
                shouldPrompt
        );
    }

    private static final String ONBOARDING_STEP_PROFILE_SETUP = OnboardingV2Stages.LEGACY_PROFILE_SETUP;
    private static final String ONBOARDING_STEP_SELECT_COMPANY = OnboardingV2Stages.LEGACY_SELECT_COMPANY;
    private static final String ONBOARDING_STEP_VERIFICATION = OnboardingV2Stages.LEGACY_VERIFICATION;
    private static final String ONBOARDING_STEP_VERIFICATION_NOTIFICATIONS = OnboardingV2Stages.LEGACY_VERIFICATION_NOTIFICATIONS;
    private static final List<String> ONBOARDING_STEP_SEQUENCE = OnboardingV2Stages.LEGACY_SEQUENCE;

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
        String step = OnboardingV2Stages.normalizeLegacyStep(user.onboardingStep);
        if (step == null) step = ONBOARDING_STEP_VERIFICATION;
        return new OnboardingState(false, step);
    }

    public OnboardingV2Service.Snapshot onboardingStateV2(String firebaseUid) {
        return onboardingV2.snapshot(firebaseUid);
    }

    public UpdateOnboardingResult updateOnboardingStep(String firebaseUid, String step) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return UpdateOnboardingResult.userNotProvisioned();

        if (actor.get().onboardingCompletedAt != null) {
            return UpdateOnboardingResult.ok(new OnboardingState(true, ONBOARDING_STEP_VERIFICATION_NOTIFICATIONS));
        }

        String normalized = normalizeOnboardingStep(step);
        if (normalized == null) {
            return UpdateOnboardingResult.invalidStep(currentOnboardingStep(actor.get()), ONBOARDING_STEP_SEQUENCE);
        }

        if (ONBOARDING_STEP_VERIFICATION_NOTIFICATIONS.equals(normalized)) {
            users.markOnboardingComplete(actor.get().id);
            users.markProfileCompletionCompletedIfEligible(actor.get().id);
            onboardingV2.syncFromLegacyStep(actor.get().id, normalized, OffsetDateTime.now());
            return UpdateOnboardingResult.ok(new OnboardingState(true, ONBOARDING_STEP_VERIFICATION_NOTIFICATIONS));
        }

        users.updateOnboardingStep(actor.get().id, normalized);
        onboardingV2.syncFromLegacyStep(actor.get().id, normalized, null);
        return UpdateOnboardingResult.ok(new OnboardingState(false, normalized));
    }

    private String normalizeOnboardingStep(String step) {
        if (step == null || step.isBlank()) return null;
        String normalized = step.trim().toLowerCase(Locale.ROOT);
        return ONBOARDING_STEP_SEQUENCE.contains(normalized) ? normalized : null;
    }

    private String currentOnboardingStep(UserRepository.UserRow actor) {
        String step = OnboardingV2Stages.normalizeLegacyStep(actor.onboardingStep);
        return step != null ? step : ONBOARDING_STEP_VERIFICATION;
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
        return onLogin(firebaseUid, null, null);
    }

    public LoginStatus onLogin(String firebaseUid, String email, Boolean emailVerified) {
        if (firebaseUid == null || firebaseUid.isBlank()) return LoginStatus.MISSING;
        var existing = users.findByFirebaseUidIncludingDeleted(firebaseUid);
        if (existing.isEmpty()) {
            var claimed = claimActiveAccountByEmail(firebaseUid, email, emailVerified);
            if (claimed.isPresent()) {
                return LoginStatus.ACTIVE;
            }
            if (deletionOperations.existsActiveByFirebaseUidOrEmail(firebaseUid, email)) {
                return LoginStatus.DELETE_PENDING;
            }
            return users.isFirebaseUidTombstoned(firebaseUid) ? LoginStatus.PURGED : LoginStatus.MISSING;
        }
        if (existing.get().deletedAt == null) return LoginStatus.ACTIVE;
        if ("admin".equalsIgnoreCase(existing.get().deletedSource)
                || "self".equalsIgnoreCase(existing.get().deletedSource)
                || "repair".equalsIgnoreCase(existing.get().deletedSource)) {
            return LoginStatus.PURGED;
        }
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
            deleted.ifPresent(ignored -> deletionOperations.markCompletedByFirebaseUid(firebaseUid));
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
                if (deleted.isPresent()) {
                    deletionOperations.markCompletedByFirebaseUid(row.firebaseUid);
                    deletedCount += 1;
                }
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

    private List<UserRepository.UserRow> excludeBlockedUsers(long actorUserId, List<UserRepository.UserRow> rows) {
        if (actorUserId <= 0 || rows == null || rows.isEmpty()) return rows == null ? List.of() : rows;
        long actorPrincipalId = principals.createForUser(actorUserId).id;
        var userIds = rows.stream().map(r -> r.id).distinct().toList();
        var principalByUser = principals.principalIdsByUserIds(userIds);
        if (principalByUser.isEmpty()) return rows;
        var candidatePrincipalIds = principalByUser.values().stream().distinct().toList();
        var blockedPrincipalIds = blocks.otherPrincipalsBlockedEitherDirection(actorPrincipalId, candidatePrincipalIds);
        if (blockedPrincipalIds.isEmpty()) return rows;
        return rows.stream().filter(r -> {
            Long principalId = principalByUser.get(r.id);
            return principalId == null || !blockedPrincipalIds.contains(principalId);
        }).toList();
    }

    private OffsetDateTime handleReuseCutoff() {
        return OffsetDateTime.now().minusDays(usernameTombstoneDays);
    }

    private Optional<UserRepository.UserRow> claimActiveAccountByEmail(String firebaseUid, String email, Boolean emailVerified) {
        if (firebaseUid == null || firebaseUid.isBlank()) return Optional.empty();
        if (email == null || email.isBlank()) return Optional.empty();
        if (Boolean.FALSE.equals(emailVerified)) return Optional.empty();
        return users.claimActiveByEmail(email, firebaseUid);
    }

    private UserProfile buildProfile(UserRepository.UserRow row, VerificationRepository.Row verification) {
        var verificationData = verification == null ? null : new Verification(verification.method, verification.verified, verification.verifiedAt);
        var displayCommunity = users.findDisplayCommunityForUser(row.id)
                .map(dc -> new DisplayCommunity(dc.id, dc.name, dc.shortName, dc.kind, dc.specializationType))
                .orElse(null);
        var displaySpecialization = users.findDisplaySpecializationForUser(row.id)
                .map(ds -> new DisplaySpecialization(ds.id, ds.name, ds.shortName, ds.kind, ds.specializationType))
                .orElse(null);
        var stats = new ProfileStats(
                users.countFollowers(row.id),
                users.countFollowing(row.id),
                users.countPosts(row.id),
                users.countComments(row.id),
                users.countLikesReceived(row.id)
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
                stats,
                row.profileCompletionDismissedAt,
                row.profileCompletionCompletedAt
        );
    }

    private String normalizeHandle(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isBlank()) return null;
        if (!trimmed.matches("^[a-z0-9_]{3,30}$")) return null;
        if (UserShareLinksService.isReservedSlug(trimmed)) return null;
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
        if (!normalized.equals("major") && !normalized.equals("field")) return null;
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

    public record ProfileResult(Status status, UserProfile profile, boolean includeFollowerCounts,
                                boolean viewerHasBlocked, boolean viewerBlockedBy) {
        static ProfileResult ok(UserProfile profile, boolean includeFollowerCounts, boolean viewerHasBlocked, boolean viewerBlockedBy) {
            return new ProfileResult(Status.OK, profile, includeFollowerCounts, viewerHasBlocked, viewerBlockedBy);
        }
        static ProfileResult userNotProvisioned() { return new ProfileResult(Status.USER_NOT_PROVISIONED, null, false, false, false); }
        static ProfileResult notFound() { return new ProfileResult(Status.NOT_FOUND, null, false, false, false); }
        static ProfileResult forbidden() { return new ProfileResult(Status.FORBIDDEN, null, false, false, false); }
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

    public record ProfileCompletionStatus(
            OffsetDateTime dismissedAt,
            OffsetDateTime completedAt,
            boolean missingPhoto,
            boolean missingBio,
            boolean missingSpecialization,
            boolean shouldPrompt
    ) {}

    public record DismissProfileCompletionResult(Status status, ProfileCompletionStatus profileCompletion) {
        static DismissProfileCompletionResult ok(ProfileCompletionStatus profileCompletion) {
            return new DismissProfileCompletionResult(Status.OK, profileCompletion);
        }

        static DismissProfileCompletionResult userNotProvisioned() {
            return new DismissProfileCompletionResult(Status.USER_NOT_PROVISIONED, null);
        }
    }

    public enum UpdateOnboardingStatus { OK, USER_NOT_PROVISIONED, INVALID_STEP }

    public record UpdateOnboardingResult(UpdateOnboardingStatus status, OnboardingState state,
                                         String currentStep, List<String> allowedNextSteps) {
        static UpdateOnboardingResult ok(OnboardingState state) {
            return new UpdateOnboardingResult(UpdateOnboardingStatus.OK, state, state.onboardingStep(), ONBOARDING_STEP_SEQUENCE);
        }
        static UpdateOnboardingResult userNotProvisioned() {
            return new UpdateOnboardingResult(UpdateOnboardingStatus.USER_NOT_PROVISIONED, null, null, List.of());
        }
        static UpdateOnboardingResult invalidStep(String currentStep, List<String> allowedNextSteps) {
            return new UpdateOnboardingResult(UpdateOnboardingStatus.INVALID_STEP, null, currentStep, allowedNextSteps);
        }
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
                              DisplaySpecialization displaySpecialization, ProfileStats stats,
                              OffsetDateTime profileCompletionDismissedAt,
                              OffsetDateTime profileCompletionCompletedAt) {}

    public record DisplayCommunity(long id, String name, String shortName, String kind, String specializationType) {}

    public record DisplaySpecialization(long id, String name, String shortName, String kind, String specializationType) {}

    public record ProfileStats(int followerCount, int followingCount, int postsCount, int commentsCount, long likesReceivedCount) {}

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

    public DeleteOperationStatusResult deleteStatus(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            return DeleteOperationStatusResult.none();
        }
        var latest = deletionOperations.latestByFirebaseUid(firebaseUid);
        if (latest.isPresent()) {
            return toDeleteOperationStatus(latest.get());
        }
        if (users.isFirebaseUidTombstoned(firebaseUid)) {
            return DeleteOperationStatusResult.legacyCompleted();
        }
        var existing = users.findByFirebaseUidIncludingDeleted(firebaseUid);
        if (existing.isPresent()
                && existing.get().deletedAt != null
                && "self".equalsIgnoreCase(existing.get().deletedSource)
                && "hard_delete_failed".equalsIgnoreCase(existing.get().deletedReason)) {
            return DeleteOperationStatusResult.legacyPending(existing.get().deletedAt);
        }
        return DeleteOperationStatusResult.none();
    }

    public DeleteResult deleteMe(String firebaseUid, DeleteMode mode) {
        var userOpt = users.findByFirebaseUidIncludingDeleted(firebaseUid);
        if (userOpt.isEmpty()) {
            if (mode == DeleteMode.SOFT) {
                return DeleteResult.ok(FirebaseDeleteStatus.NOT_REQUESTED, null, null, DeleteOperationState.NONE);
            }
            UUID operationId = deletionOperations.create(firebaseUid, null, null, "hard");
            var firebaseResult = firebaseAdmin.deleteUser(firebaseUid);
            return handleFirebaseOnlyDelete(firebaseResult, operationId);
        }
        var user = userOpt.get();
        if (mode == DeleteMode.SOFT) {
            if (user.deletedAt != null) {
                return DeleteResult.ok(FirebaseDeleteStatus.NOT_REQUESTED, null, null, DeleteOperationState.NONE);
            }
            users.softDelete(user.id, user.id);
            return DeleteResult.ok(FirebaseDeleteStatus.NOT_REQUESTED, null, null, DeleteOperationState.NONE);
        }
        UUID operationId = deletionOperations.create(firebaseUid, user.id, user.email, "hard");
        int repairedPosts = users.repairMissingAuthorIdsForUser(user.id);
        int repairedComments = users.repairMissingCommentUserIdsForUser(user.id);
        int repairedCommentLikes = users.repairMissingCommentLikeUserIdsForUser(user.id);
        if (repairedPosts > 0 || repairedComments > 0 || repairedCommentLikes > 0) {
            log.warn("delete_repair_user_fk_backfills uid={} user_id={} repaired_posts={} repaired_comments={} repaired_comment_likes={}",
                    firebaseUid, user.id, repairedPosts, repairedComments, repairedCommentLikes);
        }
        var firebaseResult = firebaseAdmin.deleteUser(firebaseUid);
        var firebaseHandled = handleFirebaseResult(firebaseResult, operationId);
        if (firebaseHandled.status() != DeleteStatus.OK) {
            return firebaseHandled;
        }
        try {
            var deleted = users.deleteById(user.id);
            deleted.ifPresent(users::insertTombstone);
            deletionOperations.markCompleted(operationId);
            return DeleteResult.ok(firebaseHandled.firebaseStatus, firebaseHandled.error, operationId, DeleteOperationState.COMPLETED);
        } catch (DataAccessException e) {
            users.markDeletedSelf(user.id, user.id, "hard_delete_failed");
            deletionOperations.markPending(operationId, "local_delete_pending", e.getMessage());
            log.error("hard_delete_failed_fallback_to_self_deleted uid={} user_id={} error={}",
                    firebaseUid, user.id, e.getMessage());
            return DeleteResult.ok(firebaseHandled.firebaseStatus, "local_delete_pending", operationId, DeleteOperationState.PENDING);
        }
    }

    private DeleteOperationStatusResult toDeleteOperationStatus(UserDeletionOperationRepository.OperationRow row) {
        if (row == null) return DeleteOperationStatusResult.none();
        DeleteOperationState state = parseDeleteOperationState(row.state);
        return new DeleteOperationStatusResult(
                state,
                row.operationId,
                row.requestedAt,
                row.updatedAt,
                row.completedAt,
                row.errorCode
        );
    }

    private DeleteOperationState parseDeleteOperationState(String raw) {
        if (raw == null || raw.isBlank()) return DeleteOperationState.NONE;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "in_progress" -> DeleteOperationState.IN_PROGRESS;
            case "pending" -> DeleteOperationState.PENDING;
            case "completed" -> DeleteOperationState.COMPLETED;
            case "failed" -> DeleteOperationState.FAILED;
            default -> DeleteOperationState.NONE;
        };
    }

    public enum DeleteMode { HARD, SOFT }

    public enum DeleteStatus { OK, FIREBASE_DELETE_FAILED, FIREBASE_DELETE_SKIPPED }

    public enum FirebaseDeleteStatus { OK, SKIPPED, FAILED, NOT_REQUESTED }

    public enum DeleteOperationState { NONE, IN_PROGRESS, PENDING, COMPLETED, FAILED }

    public record DeleteResult(DeleteStatus status,
                               FirebaseDeleteStatus firebaseStatus,
                               String error,
                               UUID operationId,
                               DeleteOperationState operationState) {
        static DeleteResult ok(FirebaseDeleteStatus firebaseStatus, String error, UUID operationId, DeleteOperationState operationState) {
            return new DeleteResult(DeleteStatus.OK, firebaseStatus, error, operationId, operationState);
        }
        static DeleteResult firebaseDeleteFailed(String error, UUID operationId) {
            return new DeleteResult(DeleteStatus.FIREBASE_DELETE_FAILED, FirebaseDeleteStatus.FAILED, error, operationId, DeleteOperationState.FAILED);
        }
        static DeleteResult firebaseDeleteSkipped(String error, UUID operationId) {
            return new DeleteResult(DeleteStatus.FIREBASE_DELETE_SKIPPED, FirebaseDeleteStatus.SKIPPED, error, operationId, DeleteOperationState.FAILED);
        }
    }

    public record DeleteOperationStatusResult(DeleteOperationState state,
                                              UUID operationId,
                                              OffsetDateTime requestedAt,
                                              OffsetDateTime updatedAt,
                                              OffsetDateTime completedAt,
                                              String errorCode) {
        static DeleteOperationStatusResult none() {
            return new DeleteOperationStatusResult(DeleteOperationState.NONE, null, null, null, null, null);
        }

        static DeleteOperationStatusResult legacyCompleted() {
            return new DeleteOperationStatusResult(DeleteOperationState.COMPLETED, null, null, null, null, null);
        }

        static DeleteOperationStatusResult legacyPending(OffsetDateTime deletedAt) {
            return new DeleteOperationStatusResult(DeleteOperationState.PENDING, null, deletedAt, deletedAt, null, "local_delete_pending");
        }
    }

    public enum LoginStatus { ACTIVE, REACTIVATED, PURGED, MISSING, PURGE_FAILED, DELETE_PENDING }

    private DeleteResult handleFirebaseResult(FirebaseAdminService.DeleteResult firebaseResult, UUID operationId) {
        return handleFirebaseResult(firebaseResult, false, operationId);
    }

    private DeleteResult handleFirebaseOnlyDelete(FirebaseAdminService.DeleteResult firebaseResult, UUID operationId) {
        return handleFirebaseResult(firebaseResult, true, operationId);
    }

    private DeleteResult handleFirebaseResult(FirebaseAdminService.DeleteResult firebaseResult, boolean firebaseOnly, UUID operationId) {
        if (firebaseResult.status() == FirebaseAdminService.DeleteStatus.OK) {
            if (firebaseOnly) {
                deletionOperations.markCompleted(operationId);
                return DeleteResult.ok(FirebaseDeleteStatus.OK, null, operationId, DeleteOperationState.COMPLETED);
            }
            return DeleteResult.ok(FirebaseDeleteStatus.OK, null, operationId, DeleteOperationState.IN_PROGRESS);
        }
        if (firebaseResult.status() == FirebaseAdminService.DeleteStatus.SKIPPED) {
            if (firebaseAdmin.isRequired()) {
                deletionOperations.markFailed(operationId, "firebase_delete_skipped", firebaseResult.error());
                return DeleteResult.firebaseDeleteSkipped(firebaseResult.error(), operationId);
            }
            if (firebaseOnly) {
                deletionOperations.markCompleted(operationId);
                return DeleteResult.ok(FirebaseDeleteStatus.SKIPPED, firebaseResult.error(), operationId, DeleteOperationState.COMPLETED);
            }
            return DeleteResult.ok(FirebaseDeleteStatus.SKIPPED, firebaseResult.error(), operationId, DeleteOperationState.IN_PROGRESS);
        }
        if (firebaseResult.status() == FirebaseAdminService.DeleteStatus.FAILED) {
            if (firebaseAdmin.isRequired()) {
                deletionOperations.markFailed(operationId, "firebase_delete_failed", firebaseResult.error());
                return DeleteResult.firebaseDeleteFailed(firebaseResult.error(), operationId);
            }
            if (firebaseOnly) {
                deletionOperations.markCompleted(operationId);
                return DeleteResult.ok(FirebaseDeleteStatus.FAILED, firebaseResult.error(), operationId, DeleteOperationState.COMPLETED);
            }
            return DeleteResult.ok(FirebaseDeleteStatus.FAILED, firebaseResult.error(), operationId, DeleteOperationState.IN_PROGRESS);
        }
        if (firebaseOnly) {
            deletionOperations.markCompleted(operationId);
            return DeleteResult.ok(FirebaseDeleteStatus.NOT_REQUESTED, null, operationId, DeleteOperationState.COMPLETED);
        }
        return DeleteResult.ok(FirebaseDeleteStatus.NOT_REQUESTED, null, operationId, DeleteOperationState.IN_PROGRESS);
    }
}
