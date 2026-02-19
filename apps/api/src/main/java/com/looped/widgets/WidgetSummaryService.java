package com.looped.widgets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.looped.media.MediaRepository;
import com.looped.posts.FeedService;
import com.looped.posts.PostRepository;
import com.looped.settings.AppConfigService;
import com.looped.users.ProfileImageUrls;
import com.looped.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;

@Service
public class WidgetSummaryService {
    private static final Logger log = LoggerFactory.getLogger(WidgetSummaryService.class);
    private static final int CONTENT_PREVIEW_MAX_CHARS = 120;

    private final UserRepository users;
    private final WidgetSummaryRepository repo;
    private final FeedService feedService;
    private final AppConfigService appConfig;
    private final MediaRepository media;
    private final String cloudfrontDomain;
    private final int snapshotTtlSeconds;

    public WidgetSummaryService(UserRepository users,
                                WidgetSummaryRepository repo,
                                FeedService feedService,
                                AppConfigService appConfig,
                                MediaRepository media,
                                @Value("${cloudfront.domain:}") String cloudfrontDomain,
                                @Value("${widgets.snapshot-ttl-seconds:900}") int snapshotTtlSeconds) {
        this.users = users;
        this.repo = repo;
        this.feedService = feedService;
        this.appConfig = appConfig;
        this.media = media;
        this.cloudfrontDomain = cloudfrontDomain == null ? "" : cloudfrontDomain.trim();
        this.snapshotTtlSeconds = Math.max(60, snapshotTtlSeconds);
    }

    public SummaryResult summary(String firebaseUid) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return SummaryResult.userNotProvisioned();

        WidgetSummaryRepository.InboxCounts inboxCounts = WidgetSummaryRepository.InboxCounts.zero();
        WidgetSummaryRepository.ProfileStats profileStats = WidgetSummaryRepository.ProfileStats.zero();
        List<WidgetSummaryRepository.VerifiedCommunityRow> communityRows = List.of();

        try {
            inboxCounts = repo.loadInboxCounts(actor.get().id);
        } catch (RuntimeException e) {
            log.warn("widget_summary_inbox_query_failed user_id={}", actor.get().id, e);
        }
        try {
            profileStats = repo.loadProfileStats(actor.get().id);
        } catch (RuntimeException e) {
            log.warn("widget_summary_profile_stats_query_failed user_id={}", actor.get().id, e);
        }
        try {
            communityRows = repo.loadVerifiedCommunities(actor.get().id);
        } catch (RuntimeException e) {
            log.warn("widget_summary_verified_communities_query_failed user_id={}", actor.get().id, e);
        }

        List<VerifiedCommunity> verified = communityRows.stream()
                .map(row -> new VerifiedCommunity(
                        row.id(),
                        row.name(),
                        row.shortName(),
                        row.memberCount(),
                        row.newActivityCount()
                ))
                .toList();

        TrendingPost trendingPost = null;
        try {
            var trending = feedService.trending(firebaseUid, 1, null);
            if (trending.status() == FeedService.Status.OK && trending.items() != null && !trending.items().isEmpty()) {
                trendingPost = toTrendingPost(trending.items().get(0));
            }
        } catch (RuntimeException e) {
            log.warn("widget_summary_trending_query_failed user_id={}", actor.get().id, e);
        }

        Long defaultCommunityId = resolveDefaultCommunityId(actor.get().displayCommunityId, verified);
        String primaryCommunityName = resolvePrimaryCommunityName(defaultCommunityId, verified);
        String specialization = resolveSpecialization(actor.get().id);
        String avatarThumbnailUrl = resolveAvatarThumbnailUrl(actor.get());
        String displayName = resolveDisplayName(actor.get());

        WidgetSummaryResponse response = new WidgetSummaryResponse(
                OffsetDateTime.now(ZoneOffset.UTC),
                snapshotTtlSeconds,
                new Inbox(
                        inboxCounts.unreadMessages(),
                        inboxCounts.messageRequests(),
                        inboxCounts.unreadMentions()
                ),
                new ProfileSummary(
                        displayName,
                        avatarThumbnailUrl,
                        specialization,
                        primaryCommunityName
                ),
                new ProfileStats(
                        profileStats.followers(),
                        profileStats.following(),
                        profileStats.likesReceived()
                ),
                verified,
                defaultCommunityId,
                trendingPost
        );
        return SummaryResult.ok(response);
    }

    public MarkCommunitySeenResult markCommunitySeen(String firebaseUid, long communityId) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return MarkCommunitySeenResult.userNotProvisioned();

        if (!repo.communityExists(communityId)) {
            return MarkCommunitySeenResult.communityNotFound();
        }
        if (!repo.isActiveVerifiedCommunity(actor.get().id, communityId)) {
            return MarkCommunitySeenResult.communityNotVerified();
        }

        OffsetDateTime seenAt = OffsetDateTime.now(ZoneOffset.UTC);
        repo.upsertCommunitySeen(actor.get().id, communityId, seenAt);
        return MarkCommunitySeenResult.ok(new CommunitySeenResponse(communityId, seenAt));
    }

    private Optional<UserRepository.UserRow> requireProvisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
    }

    private Long resolveDefaultCommunityId(Long displayCommunityId, List<VerifiedCommunity> verified) {
        if (verified == null || verified.isEmpty()) return null;
        Set<Long> verifiedIds = verified.stream().map(VerifiedCommunity::id).collect(java.util.stream.Collectors.toSet());
        if (displayCommunityId != null && verifiedIds.contains(displayCommunityId)) {
            return displayCommunityId;
        }
        return verified.get(0).id();
    }

    private String resolvePrimaryCommunityName(Long communityId, List<VerifiedCommunity> verified) {
        if (communityId == null || verified == null || verified.isEmpty()) return null;
        for (var community : verified) {
            if (community != null && community.id() == communityId) return community.name();
        }
        return null;
    }

    private String resolveSpecialization(long userId) {
        return users.findDisplaySpecializationForUser(userId)
                .map(row -> row.name)
                .filter(name -> name != null && !name.isBlank())
                .orElse(null);
    }

    private String resolveAvatarThumbnailUrl(UserRepository.UserRow actor) {
        String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
        return ProfileImageUrls.resolve(actor.profileImageUrl, defaultProfileImageUrl);
    }

    private String resolveDisplayName(UserRepository.UserRow actor) {
        if (actor.displayName != null && !actor.displayName.isBlank()) return actor.displayName;
        if (actor.firstName != null && !actor.firstName.isBlank() && actor.lastName != null && !actor.lastName.isBlank()) {
            return actor.firstName + " " + actor.lastName;
        }
        if (actor.firstName != null && !actor.firstName.isBlank()) return actor.firstName;
        if (actor.lastName != null && !actor.lastName.isBlank()) return actor.lastName;
        if (actor.handle != null && !actor.handle.isBlank()) return actor.handle;
        return null;
    }

    private TrendingPost toTrendingPost(PostRepository.TrendingRow row) {
        if (row == null) return null;
        return new TrendingPost(
                row.id,
                row.communityName,
                sanitizePreview(row.content),
                Math.max(0, row.likesCount),
                Math.max(0, row.commentsCount),
                resolveMediaThumbnailUrl(row)
        );
    }

    private String sanitizePreview(String content) {
        if (content == null) return "";
        String normalized = content
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        normalized = normalized.replaceAll("[\\p{Cntrl}]", "");
        if (normalized.length() <= CONTENT_PREVIEW_MAX_CHARS) return normalized;
        return normalized.substring(0, CONTENT_PREVIEW_MAX_CHARS - 3).trim() + "...";
    }

    private String resolveMediaThumbnailUrl(PostRepository.TrendingRow row) {
        if (row == null || cloudfrontDomain.isBlank()) return null;
        Long mediaAssetId = row.mediaAssetId;
        if (mediaAssetId == null || mediaAssetId <= 0) return null;

        var mediaRow = media.findById(mediaAssetId).orElse(null);
        if (mediaRow == null || mediaRow.s3Key == null || mediaRow.s3Key.isBlank()) return null;
        if (mediaRow.removedAt != null) return null;
        if (mediaRow.visibility != null && !"public".equalsIgnoreCase(mediaRow.visibility)) return null;

        String mime = normalizeMime(mediaRow.mimeType);
        if (mime.startsWith("video/")) {
            if (mediaRow.thumbnailMediaAssetId == null || mediaRow.thumbnailMediaAssetId <= 0) return null;
            var thumb = media.findById(mediaRow.thumbnailMediaAssetId).orElse(null);
            if (thumb == null || thumb.s3Key == null || thumb.s3Key.isBlank()) return null;
            if (thumb.removedAt != null) return null;
            if (thumb.visibility != null && !"public".equalsIgnoreCase(thumb.visibility)) return null;
            if (!normalizeMime(thumb.mimeType).startsWith("image/")) return null;
            return "https://" + cloudfrontDomain + "/" + thumb.s3Key;
        }
        if (mime.startsWith("image/")) {
            return "https://" + cloudfrontDomain + "/" + mediaRow.s3Key;
        }
        return null;
    }

    private String normalizeMime(String mime) {
        if (mime == null) return "";
        return mime.trim().toLowerCase(Locale.ROOT);
    }

    public enum SummaryStatus { OK, USER_NOT_PROVISIONED }
    public enum MarkCommunitySeenStatus { OK, USER_NOT_PROVISIONED, COMMUNITY_NOT_FOUND, COMMUNITY_NOT_VERIFIED }

    public record SummaryResult(SummaryStatus status, WidgetSummaryResponse response) {
        static SummaryResult ok(WidgetSummaryResponse response) {
            return new SummaryResult(SummaryStatus.OK, response);
        }

        static SummaryResult userNotProvisioned() {
            return new SummaryResult(SummaryStatus.USER_NOT_PROVISIONED, null);
        }
    }

    public record MarkCommunitySeenResult(MarkCommunitySeenStatus status, CommunitySeenResponse response) {
        static MarkCommunitySeenResult ok(CommunitySeenResponse response) {
            return new MarkCommunitySeenResult(MarkCommunitySeenStatus.OK, response);
        }

        static MarkCommunitySeenResult userNotProvisioned() {
            return new MarkCommunitySeenResult(MarkCommunitySeenStatus.USER_NOT_PROVISIONED, null);
        }

        static MarkCommunitySeenResult communityNotFound() {
            return new MarkCommunitySeenResult(MarkCommunitySeenStatus.COMMUNITY_NOT_FOUND, null);
        }

        static MarkCommunitySeenResult communityNotVerified() {
            return new MarkCommunitySeenResult(MarkCommunitySeenStatus.COMMUNITY_NOT_VERIFIED, null);
        }
    }

    public record WidgetSummaryResponse(
            @JsonProperty("server_time") OffsetDateTime serverTime,
            @JsonProperty("snapshot_ttl_seconds") int snapshotTtlSeconds,
            Inbox inbox,
            @JsonProperty("profile_summary") ProfileSummary profileSummary,
            @JsonProperty("profile_stats") ProfileStats profileStats,
            @JsonProperty("verified_communities") List<VerifiedCommunity> verifiedCommunities,
            @JsonProperty("default_community_id") Long defaultCommunityId,
            @JsonProperty("trending_post") TrendingPost trendingPost
    ) {}

    public record Inbox(
            @JsonProperty("unread_messages") int unreadMessages,
            @JsonProperty("message_requests") int messageRequests,
            @JsonProperty("unread_mentions") int unreadMentions
    ) {}

    public record ProfileStats(
            int followers,
            int following,
            @JsonProperty("likes_received") long likesReceived
    ) {}

    public record ProfileSummary(
            @JsonProperty("display_name") String displayName,
            @JsonProperty("avatar_thumbnail_url") String avatarThumbnailUrl,
            String specialization,
            @JsonProperty("primary_community_name") String primaryCommunityName
    ) {}

    public record VerifiedCommunity(
            long id,
            String name,
            @JsonProperty("short_name") String shortName,
            @JsonProperty("member_count") int memberCount,
            @JsonProperty("new_activity_count") int newActivityCount
    ) {}

    public record TrendingPost(
            @JsonProperty("post_id") long postId,
            @JsonProperty("community_name") String communityName,
            @JsonProperty("content_preview") String contentPreview,
            @JsonProperty("like_count") int likeCount,
            @JsonProperty("comment_count") int commentCount,
            @JsonProperty("media_thumbnail_url") String mediaThumbnailUrl
    ) {}

    public record CommunitySeenResponse(
            @JsonProperty("community_id") long communityId,
            @JsonProperty("seen_at") OffsetDateTime seenAt
    ) {}
}
