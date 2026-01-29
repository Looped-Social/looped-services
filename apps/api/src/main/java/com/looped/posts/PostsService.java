package com.looped.posts;

import com.looped.anon.AnonProofService;
import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.discovery.HashtagParser;
import com.looped.discovery.HashtagPostsRepository;
import com.looped.discovery.HashtagsRepository;
import com.looped.media.MediaRepository;
import com.looped.moderation.ContentModerationService;
import com.looped.moderation.QuarantineService;
import com.looped.notifications.NotificationPublisher;
import com.looped.polls.PollRequests;
import com.looped.polls.PollsService;
import com.looped.principals.PrincipalRepository;
import com.looped.shared.MentionParser;
import com.looped.users.BlocksRepository;
import com.looped.users.FollowsRepository;
import com.looped.users.UserCommunityBanRepository;
import com.looped.users.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class PostsService {
    private final PostRepository posts;
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final MediaRepository media;
    private final PostMediaAssetsRepository postMediaAssets;
    private final StringRedisTemplate redis;
    private final AnonProofService anonProofs;
    private final HashtagsRepository hashtags;
    private final HashtagPostsRepository hashtagPosts;
    private final FollowsRepository follows;
    private final BlocksRepository blocks;
    private final UserCommunityBanRepository communityBans;
    private final NotificationPublisher notifications;
    private final PostStateService postState;
    private final PollsService pollsService;
    private final ContentModerationService contentModeration;
    private final QuarantineService quarantine;

    public PostsService(PostRepository posts,
                        UserRepository users,
                        PrincipalRepository principals,
                        CommunitiesRepository communities,
                        CommunityVerificationsRepository communityVerifications,
                        MediaRepository media,
                        PostMediaAssetsRepository postMediaAssets,
                        StringRedisTemplate redis,
                        AnonProofService anonProofs,
                        HashtagsRepository hashtags,
                        HashtagPostsRepository hashtagPosts,
                        FollowsRepository follows,
                        BlocksRepository blocks,
                        UserCommunityBanRepository communityBans,
                        NotificationPublisher notifications,
                        PostStateService postState,
                        PollsService pollsService,
                        ContentModerationService contentModeration,
                        QuarantineService quarantine) {
        this.posts = posts;
        this.users = users;
        this.principals = principals;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.media = media;
        this.postMediaAssets = postMediaAssets;
        this.redis = redis;
        this.anonProofs = anonProofs;
        this.hashtags = hashtags;
        this.hashtagPosts = hashtagPosts;
        this.follows = follows;
        this.blocks = blocks;
        this.communityBans = communityBans;
        this.notifications = notifications;
        this.postState = postState;
        this.pollsService = pollsService;
        this.contentModeration = contentModeration;
        this.quarantine = quarantine;
    }

    @Transactional
    public CreateResult create(String firebaseUid, String idempotencyKey, String content, Long mediaAssetId, List<Long> mediaAssetIds, Long communityId,
                               boolean isAnon, PollRequests.PostPollCreate poll, Long anonProfileId, String anonCert, String anonCertKid,
                               String anonSig, Long anonTimestamp) {
        if (content == null) content = "";
        if (communityId == null) return CreateResult.communityRequired();
        var community = communities.findById(communityId);
        if (community.isEmpty()) return CreateResult.communityNotFound();

        var pollValidation = pollsService.validateCreate(poll);
        if (pollValidation.isPresent()) return CreateResult.invalidPoll(pollValidation.get());

        List<Long> normalizedMediaIds = normalizeMediaIds(mediaAssetId, mediaAssetIds);
        boolean hasText = !content.isBlank();
        boolean hasMedia = !normalizedMediaIds.isEmpty();
        boolean hasPoll = poll != null;
        if (!hasText && !hasMedia && !hasPoll) {
            return CreateResult.contentRequired();
        }
        if (normalizedMediaIds.size() > 4) {
            return CreateResult.mediaTooMany();
        }

        if (isAnon) {
            if (anonProfileId == null || anonCert == null || anonCertKid == null || anonSig == null || anonTimestamp == null) {
                return CreateResult.invalidAnonProof();
            }
            var proof = new AnonProofService.AnonPostProof(anonProfileId, anonCert, anonCertKid, anonSig);
            var verified = anonProofs.verifyPost(proof, communityId, content, anonTimestamp);
            if (verified.status() != AnonProofService.Status.OK) {
                return CreateResult.invalidAnonProof();
            }
            var decision = contentModeration.evaluateTextForAnon(content);
            if (decision.action() == ContentModerationService.Action.REJECT_ANON) {
                return CreateResult.contentUnderReview();
            }
            var mediaValidation = validatePostMedia(normalizedMediaIds, null, true);
            if (mediaValidation.underReview()) {
                return CreateResult.contentUnderReview();
            }
            if (mediaValidation.status() != MediaValidationStatus.OK) {
                return toCreateResult(mediaValidation);
            }
            if (verified.actor().companyId() == null) {
                return CreateResult.invalidAnonProof();
            }

            byte[] certBytes;
            byte[] sigBytes;
            try {
                certBytes = Base64.getDecoder().decode(anonCert);
                sigBytes = Base64.getDecoder().decode(anonSig);
            } catch (IllegalArgumentException e) {
                return CreateResult.invalidAnonProof();
            }

            long effectiveCompanyId = verified.actor().companyId();
            Long primaryMediaAssetId = mediaValidation.primaryId();
            var p = posts.insert(null, verified.actor().principalId(), effectiveCompanyId, communityId, content, primaryMediaAssetId,
                    true, anonProfileId, effectiveCompanyId, certBytes, anonCertKid, sigBytes, null);
            postMediaAssets.insert(p.id, normalizedMediaIds);
            var refreshed = posts.findById(p.id).orElse(p);
            if (poll != null) {
                pollsService.createForPost(refreshed.id, poll);
            }
            indexHashtags(refreshed.id, effectiveCompanyId, content);
            try {
                notifyPostFromFollowed(refreshed.authorPrincipalId, refreshed.id);
                notifyMentions(refreshed.authorPrincipalId, null, effectiveCompanyId, content, refreshed.id);
            } catch (RuntimeException ignored) {}
            postState.applyForPrincipal(verified.actor().principalId(), java.util.List.of(refreshed));
            return CreateResult.ok(refreshed, true);
        }

        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return CreateResult.userNotProvisioned();
        long userId = u.get().id;
        long companyId = Optional.ofNullable(u.get().companyId).orElse(0L);

        if (communityBans.isBanned(userId, communityId)) {
            return CreateResult.communityBanned();
        }
        if (requiresVerification(community.get()) && !communityVerifications.isVerified(userId, communityId)) {
            return CreateResult.notVerified();
        }

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return CreateResult.idempotencyRequired();
        }

        var principal = principals.createForUser(userId);

        var mediaValidation = validatePostMedia(normalizedMediaIds, userId, false);
        if (mediaValidation.status() != MediaValidationStatus.OK) {
            return toCreateResult(mediaValidation);
        }

        String redisKey = "idem:posts:" + userId + ":" + idempotencyKey;
        boolean useIdem = true;
        try {
            Boolean reserved = redis.opsForValue().setIfAbsent(redisKey, "PENDING", Duration.ofHours(24));
            if (Boolean.FALSE.equals(reserved)) {
                String val = redis.opsForValue().get(redisKey);
                if (val != null && !val.equals("PENDING")) {
                    try {
                        long postId = Long.parseLong(val);
                        var existing = posts.findById(postId).orElse(null);
                        if (existing != null) return CreateResult.ok(existing, false);
                    } catch (NumberFormatException ignored) {}
                }
                return CreateResult.inFlight();
            }
        } catch (RuntimeException e) {
            useIdem = false;
        }

        try {
            var decision = contentModeration.evaluateText(content);
            boolean mediaUnderReview = mediaValidation.underReview();
            boolean quarantinePost = decision.action() == ContentModerationService.Action.QUARANTINE || mediaUnderReview;
            Long primaryMediaAssetId = mediaValidation.primaryId();
            var p = posts.insert(userId, principal.id, companyId, communityId, content, primaryMediaAssetId,
                    false, null, null, null, null, null, null);
            postMediaAssets.insert(p.id, normalizedMediaIds);
            var refreshed = posts.findById(p.id).orElse(p);
            if (poll != null) {
                pollsService.createForPost(refreshed.id, poll);
            }
            if (quarantinePost) {
                String qSource = decision.action() == ContentModerationService.Action.QUARANTINE ? decision.source() : "media";
                String qReason = decision.action() == ContentModerationService.Action.QUARANTINE ? decision.reason() : "policy:media_under_review";
                quarantine.quarantinePost(refreshed.id, qSource, qReason);
                hashtagPosts.deleteByPostId(refreshed.id);
                refreshed = posts.findById(refreshed.id).orElse(refreshed);
            } else {
                indexHashtags(refreshed.id, companyId, content);
            }
            if (useIdem) {
                try {
                    redis.opsForValue().set(redisKey, Long.toString(refreshed.id), Duration.ofHours(24));
                } catch (RuntimeException ignored) {}
            }
            if (!quarantinePost) {
                try {
                    notifyPostFromFollowed(refreshed.authorPrincipalId, refreshed.id);
                    notifyMentions(refreshed.authorPrincipalId, userId, companyId, content, refreshed.id);
                } catch (RuntimeException ignored) {}
            }
            postState.applyForPrincipal(principal.id, java.util.List.of(refreshed));
            return CreateResult.ok(refreshed, true);
        } catch (DataAccessException e) {
            if (useIdem) {
                try { redis.delete(redisKey); } catch (RuntimeException ignored) {}
            }
            throw e;
        }
    }

    private static List<Long> normalizeMediaIds(Long mediaAssetId, List<Long> mediaAssetIds) {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        if (mediaAssetIds != null && !mediaAssetIds.isEmpty()) {
            for (Long id : mediaAssetIds) {
                if (id != null && id > 0) out.add(id);
            }
        } else if (mediaAssetId != null && mediaAssetId > 0) {
            out.add(mediaAssetId);
        }
        return new ArrayList<>(out);
    }

    private MediaValidation validatePostMedia(List<Long> mediaAssetIds, Long expectedOwnerId, boolean requireNullOwner) {
        if (mediaAssetIds == null || mediaAssetIds.isEmpty()) {
            return MediaValidation.ok(null, false);
        }
        var rows = media.findByIds(mediaAssetIds);
        if (rows.size() != mediaAssetIds.size()) {
            return MediaValidation.notFound();
        }

        boolean hasImage = false;
        boolean hasVideo = false;
        boolean underReview = false;
        for (var row : rows) {
            String mt = row.mimeType == null ? null : row.mimeType.toLowerCase(Locale.ROOT);
            if (mt != null && mt.startsWith("image/")) hasImage = true;
            else if (mt != null && mt.startsWith("video/")) hasVideo = true;
            else return MediaValidation.invalidType();
            if (row.removedAt != null) return MediaValidation.notFound();
            if (row.visibility != null && !row.visibility.equalsIgnoreCase("public")) underReview = true;

            if (requireNullOwner) {
                if (row.ownerId != null) return MediaValidation.anonNotAllowed();
            } else if (expectedOwnerId != null) {
                if (row.ownerId == null || !row.ownerId.equals(expectedOwnerId)) return MediaValidation.notOwned();
            }
        }

        if (hasVideo && mediaAssetIds.size() > 1) {
            return MediaValidation.mixed();
        }
        if (hasVideo && hasImage) {
            return MediaValidation.mixed();
        }
        if (hasImage && mediaAssetIds.size() > 4) {
            return MediaValidation.tooMany();
        }
        return MediaValidation.ok(mediaAssetIds.get(0), underReview);
    }

    private static CreateResult toCreateResult(MediaValidation validation) {
        return switch (validation.status()) {
            case NOT_FOUND -> CreateResult.mediaNotFound();
            case INVALID_TYPE, MIXED_TYPES -> CreateResult.mediaInvalid();
            case TOO_MANY -> CreateResult.mediaTooMany();
            case NOT_OWNED -> CreateResult.mediaNotOwned();
            case ANON_NOT_ALLOWED -> CreateResult.anonMediaNotAllowed();
            case OK -> throw new IllegalStateException("Unexpected OK media validation in error mapper");
        };
    }

    private enum MediaValidationStatus { OK, NOT_FOUND, INVALID_TYPE, MIXED_TYPES, TOO_MANY, NOT_OWNED, ANON_NOT_ALLOWED }

    private record MediaValidation(MediaValidationStatus status, Long primaryId, boolean underReview) {
        static MediaValidation ok(Long primaryId, boolean underReview) { return new MediaValidation(MediaValidationStatus.OK, primaryId, underReview); }
        static MediaValidation notFound() { return new MediaValidation(MediaValidationStatus.NOT_FOUND, null, false); }
        static MediaValidation invalidType() { return new MediaValidation(MediaValidationStatus.INVALID_TYPE, null, false); }
        static MediaValidation mixed() { return new MediaValidation(MediaValidationStatus.MIXED_TYPES, null, false); }
        static MediaValidation tooMany() { return new MediaValidation(MediaValidationStatus.TOO_MANY, null, false); }
        static MediaValidation notOwned() { return new MediaValidation(MediaValidationStatus.NOT_OWNED, null, false); }
        static MediaValidation anonNotAllowed() { return new MediaValidation(MediaValidationStatus.ANON_NOT_ALLOWED, null, false); }
    }

    public Optional<PostRepository.PostRow> get(long id) {
        return posts.findById(id);
    }

    public GetResult getScoped(String firebaseUid, long id) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return GetResult.userNotProvisioned();
        var p = posts.findById(id);
        if (p.isEmpty()) return GetResult.notFound();
        var principal = principals.createForUser(u.get().id);
        if (p.get().visibility != null && !p.get().visibility.equalsIgnoreCase("public")
                && principal.id != p.get().authorPrincipalId) {
            return GetResult.notFound();
        }
        if (blocks.existsEitherDirection(principal.id, p.get().authorPrincipalId)) {
            return GetResult.notFound();
        }
        if (u.get().hideAnonymousPosts && p.get().authorIsAnonymous && (p.get().authorId == null || p.get().authorId != u.get().id)) {
            return GetResult.notFound();
        }
        if (p.get().communityId != null && communityBans.isBanned(u.get().id, p.get().communityId)) {
            return GetResult.communityBanned();
        }
        postState.applyForPrincipal(principal.id, java.util.List.of(p.get()));
        return GetResult.ok(p.get());
    }

    @Transactional
    public EditResult edit(String firebaseUid, long postId, String content, AnonProofService.AnonActionProof anonProof) {
        var post = posts.findByIdIncludingRemoved(postId);
        if (post.isEmpty()) return EditResult.notFound();
        if (post.get().removedAt != null) return EditResult.postRemoved();

        long actorPrincipalId;
        ContentModerationService.Decision decision;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            if (post.get().communityId == null) return EditResult.invalidAnonProof();
            var verified = anonProofs.verifyActionScoped(anonProof, "post_edit", postId, post.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return EditResult.invalidAnonProof();
            actorPrincipalId = verified.actor().principalId();
            decision = contentModeration.evaluateTextForAnon(content);
        } else {
            if (firebaseUid == null) return EditResult.userNotProvisioned();
            var actor = users.findByFirebaseUid(firebaseUid);
            if (actor.isEmpty() || actor.get().companyId == null) return EditResult.userNotProvisioned();
            actorPrincipalId = principals.createForUser(actor.get().id).id;
            decision = contentModeration.evaluateText(content);
        }

        if (actorPrincipalId != post.get().authorPrincipalId) return EditResult.forbidden();
        if (decision.action() == ContentModerationService.Action.REJECT_ANON) {
            return EditResult.contentUnderReview();
        }

        boolean updated = posts.updateContent(postId, content);
        if (!updated) return EditResult.postRemoved();

        hashtagPosts.deleteByPostId(postId);
        boolean shouldQuarantine = decision.action() == ContentModerationService.Action.QUARANTINE;
        if (!shouldQuarantine && (post.get().visibility == null || post.get().visibility.equalsIgnoreCase("public"))) {
            indexHashtags(postId, post.get().companyId, content);
        }
        if (shouldQuarantine && (post.get().visibility == null || post.get().visibility.equalsIgnoreCase("public"))) {
            quarantine.quarantinePost(postId, decision.source(), decision.reason());
        }

        var updatedPost = posts.findById(postId).orElseThrow();
        postState.applyForPrincipal(actorPrincipalId, java.util.List.of(updatedPost));
        return EditResult.ok(updatedPost);
    }

    public DeleteResult delete(String firebaseUid, long postId, AnonProofService.AnonActionProof anonProof) {
        var post = posts.findByIdIncludingRemoved(postId);
        if (post.isEmpty()) return DeleteResult.notFound();

        long actorPrincipalId;
        if (anonProof != null && anonProof.anonProfileId() != null) {
            if (post.get().communityId == null) return DeleteResult.invalidAnonProof();
            var verified = anonProofs.verifyActionScoped(anonProof, "post_delete", postId, post.get().communityId);
            if (verified.status() != AnonProofService.Status.OK) return DeleteResult.invalidAnonProof();
            actorPrincipalId = verified.actor().principalId();
        } else {
            if (firebaseUid == null) return DeleteResult.userNotProvisioned();
            var actor = users.findByFirebaseUid(firebaseUid);
            if (actor.isEmpty() || actor.get().companyId == null) return DeleteResult.userNotProvisioned();
            actorPrincipalId = principals.createForUser(actor.get().id).id;
        }

        if (actorPrincipalId != post.get().authorPrincipalId) return DeleteResult.forbidden();
        if (post.get().removedAt != null) return DeleteResult.ok(false);

        boolean removed = posts.remove(postId, null, "user_deleted");
        return DeleteResult.ok(removed);
    }


    private boolean requiresVerification(CommunitiesRepository.CommunityRow community) {
        return community != null && !"specialization".equalsIgnoreCase(community.kind);
    }

    private void indexHashtags(long postId, long companyId, String content) {
        var tags = HashtagParser.extract(content);
        if (tags.isEmpty()) return;
        for (String tag : tags) {
            long hashtagId = hashtags.upsert(companyId, tag);
            hashtagPosts.attach(hashtagId, postId);
        }
    }

    private void notifyPostFromFollowed(long authorPrincipalId, long postId) {
        var followerUserIds = follows.findFollowerUserIds(authorPrincipalId);
        notifications.notifyPostFromFollowed(authorPrincipalId, postId, followerUserIds);
    }

    private void notifyMentions(long actorPrincipalId, Long actorUserId, long companyId, String content, long postId) {
        var handles = MentionParser.extract(content);
        if (handles.isEmpty()) return;
        var mentioned = users.findByHandlesInCompany(companyId, handles);
        if (mentioned.isEmpty()) return;
        java.util.List<Long> userIds = mentioned.stream()
                .map(u -> u.id)
                .filter(id -> actorUserId == null || id != actorUserId)
                .distinct()
                .toList();
        notifications.notifyMentions(actorPrincipalId, userIds, postId, null);
    }

    public record GetResult(Status status, PostRepository.PostRow post) {
        static GetResult ok(PostRepository.PostRow p) { return new GetResult(Status.OK, p); }
        static GetResult userNotProvisioned() { return new GetResult(Status.USER_NOT_PROVISIONED, null); }
        static GetResult forbidden() { return new GetResult(Status.FORBIDDEN, null); }
        static GetResult notFound() { return new GetResult(Status.NOT_FOUND, null); }
        static GetResult communityBanned() { return new GetResult(Status.COMMUNITY_BANNED, null); }
    }

    public enum Status {
        OK,
        USER_NOT_PROVISIONED,
        IDEMPOTENCY_IN_FLIGHT,
        IDEMPOTENCY_REQUIRED,
        CONTENT_REQUIRED,
        INVALID_POLL,
        INVALID_ANON_PROOF,
        CONTENT_UNDER_REVIEW,
        ANON_MEDIA_NOT_ALLOWED,
        MEDIA_TOO_MANY,
        MEDIA_NOT_FOUND,
        MEDIA_INVALID,
        MEDIA_NOT_OWNED,
        FORBIDDEN,
        NOT_FOUND,
        COMMUNITY_REQUIRED,
        COMMUNITY_NOT_FOUND,
        COMMUNITY_BANNED,
        NOT_VERIFIED
    }
    public record CreateResult(Status status, PostRepository.PostRow post, boolean created) {
        static CreateResult ok(PostRepository.PostRow post, boolean created) { return new CreateResult(Status.OK, post, created); }
        static CreateResult userNotProvisioned() { return new CreateResult(Status.USER_NOT_PROVISIONED, null, false); }
        static CreateResult inFlight() { return new CreateResult(Status.IDEMPOTENCY_IN_FLIGHT, null, false); }
        static CreateResult idempotencyRequired() { return new CreateResult(Status.IDEMPOTENCY_REQUIRED, null, false); }
        static CreateResult contentRequired() { return new CreateResult(Status.CONTENT_REQUIRED, null, false); }
        static CreateResult invalidPoll(String ignored) { return new CreateResult(Status.INVALID_POLL, null, false); }
        static CreateResult invalidAnonProof() { return new CreateResult(Status.INVALID_ANON_PROOF, null, false); }
        static CreateResult contentUnderReview() { return new CreateResult(Status.CONTENT_UNDER_REVIEW, null, false); }
        static CreateResult anonMediaNotAllowed() { return new CreateResult(Status.ANON_MEDIA_NOT_ALLOWED, null, false); }
        static CreateResult mediaTooMany() { return new CreateResult(Status.MEDIA_TOO_MANY, null, false); }
        static CreateResult mediaNotFound() { return new CreateResult(Status.MEDIA_NOT_FOUND, null, false); }
        static CreateResult mediaInvalid() { return new CreateResult(Status.MEDIA_INVALID, null, false); }
        static CreateResult mediaNotOwned() { return new CreateResult(Status.MEDIA_NOT_OWNED, null, false); }
        static CreateResult communityRequired() { return new CreateResult(Status.COMMUNITY_REQUIRED, null, false); }
        static CreateResult communityNotFound() { return new CreateResult(Status.COMMUNITY_NOT_FOUND, null, false); }
        static CreateResult communityBanned() { return new CreateResult(Status.COMMUNITY_BANNED, null, false); }
        static CreateResult notVerified() { return new CreateResult(Status.NOT_VERIFIED, null, false); }
    }

    public enum EditStatus { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN, INVALID_ANON_PROOF, POST_REMOVED, CONTENT_UNDER_REVIEW }

    public record EditResult(EditStatus status, PostRepository.PostRow post) {
        static EditResult ok(PostRepository.PostRow post) { return new EditResult(EditStatus.OK, post); }
        static EditResult userNotProvisioned() { return new EditResult(EditStatus.USER_NOT_PROVISIONED, null); }
        static EditResult notFound() { return new EditResult(EditStatus.NOT_FOUND, null); }
        static EditResult forbidden() { return new EditResult(EditStatus.FORBIDDEN, null); }
        static EditResult invalidAnonProof() { return new EditResult(EditStatus.INVALID_ANON_PROOF, null); }
        static EditResult postRemoved() { return new EditResult(EditStatus.POST_REMOVED, null); }
        static EditResult contentUnderReview() { return new EditResult(EditStatus.CONTENT_UNDER_REVIEW, null); }
    }

    public enum DeleteStatus { OK, USER_NOT_PROVISIONED, NOT_FOUND, FORBIDDEN, INVALID_ANON_PROOF }

    public record DeleteResult(DeleteStatus status, boolean deleted) {
        static DeleteResult ok(boolean deleted) { return new DeleteResult(DeleteStatus.OK, deleted); }
        static DeleteResult userNotProvisioned() { return new DeleteResult(DeleteStatus.USER_NOT_PROVISIONED, false); }
        static DeleteResult notFound() { return new DeleteResult(DeleteStatus.NOT_FOUND, false); }
        static DeleteResult forbidden() { return new DeleteResult(DeleteStatus.FORBIDDEN, false); }
        static DeleteResult invalidAnonProof() { return new DeleteResult(DeleteStatus.INVALID_ANON_PROOF, false); }
    }
}
