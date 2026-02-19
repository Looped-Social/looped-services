package com.looped.widgets;

import com.fasterxml.jackson.annotation.JsonProperty;
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

@Service
public class WidgetSummaryService {
    private static final Logger log = LoggerFactory.getLogger(WidgetSummaryService.class);

    private final UserRepository users;
    private final WidgetSummaryRepository repo;
    private final int snapshotTtlSeconds;

    public WidgetSummaryService(UserRepository users,
                                WidgetSummaryRepository repo,
                                @Value("${widgets.snapshot-ttl-seconds:900}") int snapshotTtlSeconds) {
        this.users = users;
        this.repo = repo;
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

        Long defaultCommunityId = resolveDefaultCommunityId(actor.get().displayCommunityId, verified);

        WidgetSummaryResponse response = new WidgetSummaryResponse(
                OffsetDateTime.now(ZoneOffset.UTC),
                snapshotTtlSeconds,
                new Inbox(
                        inboxCounts.unreadMessages(),
                        inboxCounts.messageRequests(),
                        inboxCounts.unreadMentions()
                ),
                new ProfileStats(
                        profileStats.followers(),
                        profileStats.following(),
                        profileStats.likesReceived()
                ),
                verified,
                defaultCommunityId
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
            @JsonProperty("profile_stats") ProfileStats profileStats,
            @JsonProperty("verified_communities") List<VerifiedCommunity> verifiedCommunities,
            @JsonProperty("default_community_id") Long defaultCommunityId
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

    public record VerifiedCommunity(
            long id,
            String name,
            @JsonProperty("short_name") String shortName,
            @JsonProperty("member_count") int memberCount,
            @JsonProperty("new_activity_count") int newActivityCount
    ) {}

    public record CommunitySeenResponse(
            @JsonProperty("community_id") long communityId,
            @JsonProperty("seen_at") OffsetDateTime seenAt
    ) {}
}
