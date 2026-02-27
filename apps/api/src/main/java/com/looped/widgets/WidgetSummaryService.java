package com.looped.widgets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.looped.media.MediaRepository;
import com.looped.messaging.ConversationRepository;
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
    private static final int RECENT_CHATS_LIMIT = 3;
    private static final int RECENT_CHAT_PREVIEW_MAX_CHARS = 80;

    private final UserRepository users;
    private final WidgetSummaryRepository repo;
    private final FeedService feedService;
    private final ConversationRepository conversations;
    private final AppConfigService appConfig;
    private final MediaRepository media;
    private final String cloudfrontDomain;
    private final int snapshotTtlSeconds;

    public WidgetSummaryService(UserRepository users,
                                WidgetSummaryRepository repo,
                                FeedService feedService,
                                ConversationRepository conversations,
                                AppConfigService appConfig,
                                MediaRepository media,
                                @Value("${cloudfront.domain:}") String cloudfrontDomain,
                                @Value("${widgets.snapshot-ttl-seconds:900}") int snapshotTtlSeconds) {
        this.users = users;
        this.repo = repo;
        this.feedService = feedService;
        this.conversations = conversations;
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
        List<RecentChat> recentChats = List.of();

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
        try {
            recentChats = loadRecentChats(actor.get().id);
        } catch (RuntimeException e) {
            log.warn("widget_summary_recent_chats_query_failed user_id={}", actor.get().id, e);
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
                recentChats,
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
        OffsetDateTime effectiveSeenAt = repo.upsertCommunitySeen(actor.get().id, communityId, seenAt);
        return MarkCommunitySeenResult.ok(new CommunitySeenResponse(communityId, effectiveSeenAt == null ? seenAt : effectiveSeenAt));
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
        return resolveWidgetProfileImage(actor.profileImageUrl, defaultProfileImageUrl);
    }

    private String resolveDisplayName(UserRepository.UserRow actor) {
        if (actor.displayName != null && !actor.displayName.isBlank()) return actor.displayName;
        boolean placeholderIdentity = isPlaceholderIdentity(actor.firstName, actor.lastName);
        if (!placeholderIdentity
                && actor.firstName != null && !actor.firstName.isBlank()
                && actor.lastName != null && !actor.lastName.isBlank()) {
            return actor.firstName + " " + actor.lastName;
        }
        if (!placeholderIdentity && actor.firstName != null && !actor.firstName.isBlank()) return actor.firstName;
        if (!placeholderIdentity && actor.lastName != null && !actor.lastName.isBlank()) return actor.lastName;
        if (actor.handle != null && !actor.handle.isBlank()) return actor.handle;
        return null;
    }

    private boolean isPlaceholderIdentity(String firstName, String lastName) {
        return firstName != null
                && lastName != null
                && "unknown".equalsIgnoreCase(firstName.trim())
                && "user".equalsIgnoreCase(lastName.trim());
    }

    private List<RecentChat> loadRecentChats(long userId) {
        String defaultProfileImageUrl = appConfig.defaultProfileImageUrl();
        return conversations.listForUser(userId, null, null, RECENT_CHATS_LIMIT).stream()
                .map(row -> new RecentChat(
                        row.id,
                        conversationTitle(row),
                        resolveWidgetProfileImage(row.otherUserProfileImageUrl, defaultProfileImageUrl),
                        sanitizeRecentChatPreview(row.lastMessage),
                        Math.max(0, row.unreadCount)
                ))
                .toList();
    }

    private String conversationTitle(ConversationRepository.ConversationSummary row) {
        if (row == null) return null;
        if (row.otherUserDisplayName != null && !row.otherUserDisplayName.isBlank()) return row.otherUserDisplayName;
        if (row.otherUserHandle != null && !row.otherUserHandle.isBlank()) return row.otherUserHandle;
        return "Chat";
    }

    private String sanitizeRecentChatPreview(String content) {
        String normalized = sanitizeText(content, RECENT_CHAT_PREVIEW_MAX_CHARS);
        if (normalized == null || normalized.isBlank()) return "";
        return normalized;
    }

    private TrendingPost toTrendingPost(PostRepository.TrendingRow row) {
        if (row == null) return null;
        return new TrendingPost(
                row.id,
                row.communityName,
                sanitizePreview(row.content),
                Math.max(0, row.likesCount),
                Math.max(0, row.commentsCount),
                resolveWidgetImageUrl(resolveMediaThumbnailUrl(row))
        );
    }

    private String sanitizePreview(String content) {
        String normalized = sanitizeText(content, CONTENT_PREVIEW_MAX_CHARS);
        return normalized == null ? "" : normalized;
    }

    private String sanitizeText(String content, int maxChars) {
        if (content == null) return null;
        String normalized = content
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        normalized = normalized.replaceAll("[\\p{Cntrl}]", "");
        if (normalized.length() <= maxChars) return normalized;
        return normalized.substring(0, maxChars - 3).trim() + "...";
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

    private String resolveWidgetImageUrl(String rawUrl) {
        if (rawUrl == null) return null;
        String trimmed = rawUrl.trim();
        if (trimmed.isBlank()) return null;

        String normalized = trimmed;
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            String cdn = buildCdnUrlFromKey(normalized);
            if (cdn == null) {
                log.warn("widget_summary_image_rejected reason=relative_or_missing_cdn url={}", rawUrl);
            }
            return cdn;
        }

        java.net.URI uri;
        try {
            uri = java.net.URI.create(normalized);
        } catch (IllegalArgumentException e) {
            log.warn("widget_summary_image_rejected reason=invalid_uri url={}", rawUrl);
            return null;
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            String cdn = buildCdnUrlFromKey(normalized);
            if (cdn == null) {
                log.warn("widget_summary_image_rejected reason=missing_host url={}", rawUrl);
            }
            return cdn;
        }

        if (!scheme.equalsIgnoreCase("https")) {
            if (scheme.equalsIgnoreCase("http")) {
                normalized = "https://" + host + uri.getRawPath() +
                        (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                try {
                    uri = java.net.URI.create(normalized);
                } catch (IllegalArgumentException e) {
                    log.warn("widget_summary_image_rejected reason=invalid_https_upgrade url={}", rawUrl);
                    return null;
                }
            } else {
                log.warn("widget_summary_image_rejected reason=non_https url={}", rawUrl);
                return null;
            }
        }

        boolean isCdnHost = !cloudfrontDomain.isBlank() && host.equalsIgnoreCase(cloudfrontDomain);
        if (!isCdnHost && isPrivateHost(host)) {
            log.warn("widget_summary_image_rejected reason=private_host host={}", host);
            return null;
        }

        if (looksLikeS3Host(host) && !cloudfrontDomain.isBlank()) {
            String key = extractObjectKey(uri, host);
            String cdn = buildCdnUrlFromKey(key);
            if (cdn != null) return cdn;
        }

        if (isSignedUrl(uri)) {
            long minExpirySeconds = Math.max(86_400L, (long) snapshotTtlSeconds * 2L);
            Long expirySeconds = signedUrlExpirySeconds(uri);
            if (expirySeconds != null && expirySeconds < minExpirySeconds) {
                log.warn("widget_summary_image_rejected reason=signed_url_short_expiry expiry_seconds={} url={}",
                        expirySeconds, rawUrl);
                return null;
            }
        }

        return normalized;
    }

    private String resolveWidgetProfileImage(String profileImageUrl, String defaultProfileImageUrl) {
        String resolved = resolveWidgetImageUrl(profileImageUrl);
        if (resolved != null) return resolved;
        return resolveWidgetImageUrl(defaultProfileImageUrl);
    }

    private boolean looksLikeS3Host(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("s3.amazonaws.com")
                || h.endsWith(".s3.amazonaws.com")
                || h.contains(".s3-")
                || h.endsWith(".amazonaws.com") && h.contains("s3");
    }

    private String extractObjectKey(java.net.URI uri, String host) {
        String path = uri.getRawPath();
        if (path == null) return null;
        String key = path.startsWith("/") ? path.substring(1) : path;
        if (host.equalsIgnoreCase("s3.amazonaws.com")) {
            int slash = key.indexOf('/');
            if (slash >= 0 && slash + 1 < key.length()) {
                key = key.substring(slash + 1);
            } else {
                key = "";
            }
        }
        return key;
    }

    private String buildCdnUrlFromKey(String key) {
        if (key == null) return null;
        String normalized = key.trim();
        if (normalized.isBlank()) return null;
        if (normalized.startsWith("https://")) return normalized;
        if (cloudfrontDomain.isBlank()) return null;
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.isBlank()) return null;
        return "https://" + cloudfrontDomain + "/" + normalized;
    }

    private boolean isPrivateHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".localdomain") || h.endsWith(".internal")) {
            return true;
        }
        if (isIpLiteral(h)) {
            try {
                java.net.InetAddress addr = java.net.InetAddress.getByName(h);
                return addr.isAnyLocalAddress()
                        || addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress();
            } catch (Exception ignored) {
                return true;
            }
        }
        return false;
    }

    private boolean isIpLiteral(String host) {
        if (host == null || host.isBlank()) return false;
        if (host.contains(":")) return true;
        return host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
    }

    private boolean isSignedUrl(java.net.URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) return false;
        String q = query.toLowerCase(Locale.ROOT);
        return q.contains("x-amz-signature")
                || q.contains("x-amz-credential")
                || q.contains("x-amz-expires")
                || q.contains("expires=")
                || q.contains("signature=")
                || q.contains("policy=");
    }

    private Long signedUrlExpirySeconds(java.net.URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) return null;
        java.util.Map<String, String> params = parseQueryParams(query);

        String expires = firstParam(params, "X-Amz-Expires", "x-amz-expires");
        String amzDate = firstParam(params, "X-Amz-Date", "x-amz-date");
        String cfExpires = firstParam(params, "Expires", "expires");

        if (expires != null && amzDate != null) {
            try {
                java.time.OffsetDateTime start = java.time.OffsetDateTime.parse(
                        amzDate,
                        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX")
                );
                long seconds = Long.parseLong(expires);
                long diff = java.time.Duration.between(java.time.OffsetDateTime.now(ZoneOffset.UTC), start.plusSeconds(seconds)).toSeconds();
                return Math.max(0, diff);
            } catch (RuntimeException ignored) {
                // fall through
            }
        }

        if (cfExpires != null) {
            try {
                long epochSeconds = Long.parseLong(cfExpires);
                long now = java.time.Instant.now().getEpochSecond();
                return Math.max(0, epochSeconds - now);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    private java.util.Map<String, String> parseQueryParams(String rawQuery) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            if (pair.isBlank()) continue;
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String val = idx >= 0 ? pair.substring(idx + 1) : "";
            try {
                key = java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8);
                val = java.net.URLDecoder.decode(val, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // keep raw
            }
            out.put(key, val);
        }
        return out;
    }

    private String firstParam(java.util.Map<String, String> params, String... keys) {
        for (String key : keys) {
            if (params.containsKey(key)) return params.get(key);
        }
        return null;
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
            @JsonProperty("recent_chats") List<RecentChat> recentChats,
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

    public record RecentChat(
            @JsonProperty("conversation_id") long conversationId,
            String title,
            @JsonProperty("avatar_thumbnail_url") String avatarThumbnailUrl,
            @JsonProperty("last_message_preview") String lastMessagePreview,
            @JsonProperty("unread_count") int unreadCount
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
