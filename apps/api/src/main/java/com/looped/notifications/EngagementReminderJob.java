package com.looped.notifications;

import com.looped.posts.FeedService;
import com.looped.posts.PostRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EngagementReminderJob {
    private final NotificationEngagementRepository engagement;
    private final FeedService feed;
    private final NotificationPublisher notifications;

    private final boolean enabled;
    private final boolean sinceAwayEnabled;
    private final int sinceAwayHours;
    private final int sinceAwayMinNewPosts;
    private final int sinceAwayBatchSize;

    private final boolean trendingEnabled;
    private final int trendingAwayHours;
    private final int trendingMinEngagements;
    private final int trendingBatchSize;
    private final int trendingMaxUsersPerRun;

    public EngagementReminderJob(NotificationEngagementRepository engagement,
                                 FeedService feed,
                                 NotificationPublisher notifications,
                                 @Value("${notifications.engagement.enabled:true}") boolean enabled,
                                 @Value("${notifications.engagement.since-away.enabled:true}") boolean sinceAwayEnabled,
                                 @Value("${notifications.engagement.since-away.min-away-hours:6}") int sinceAwayHours,
                                 @Value("${notifications.engagement.since-away.min-new-posts:6}") int sinceAwayMinNewPosts,
                                 @Value("${notifications.engagement.since-away.batch-size:200}") int sinceAwayBatchSize,
                                 @Value("${notifications.engagement.trending.enabled:true}") boolean trendingEnabled,
                                 @Value("${notifications.engagement.trending.min-away-hours:6}") int trendingAwayHours,
                                 @Value("${notifications.engagement.trending.min-engagements:10}") int trendingMinEngagements,
                                 @Value("${notifications.engagement.trending.batch-size:100}") int trendingBatchSize,
                                 @Value("${notifications.engagement.trending.max-users-per-run:500}") int trendingMaxUsersPerRun) {
        this.engagement = engagement;
        this.feed = feed;
        this.notifications = notifications;

        this.enabled = enabled;
        this.sinceAwayEnabled = sinceAwayEnabled;
        this.sinceAwayHours = Math.max(1, Math.min(sinceAwayHours, 24 * 30));
        this.sinceAwayMinNewPosts = Math.max(1, Math.min(sinceAwayMinNewPosts, 100));
        this.sinceAwayBatchSize = Math.max(25, Math.min(sinceAwayBatchSize, 2_000));

        this.trendingEnabled = trendingEnabled;
        this.trendingAwayHours = Math.max(1, Math.min(trendingAwayHours, 24 * 30));
        this.trendingMinEngagements = Math.max(1, Math.min(trendingMinEngagements, 10_000));
        this.trendingBatchSize = Math.max(25, Math.min(trendingBatchSize, 500));
        this.trendingMaxUsersPerRun = Math.max(25, Math.min(trendingMaxUsersPerRun, 10_000));
    }

    @Scheduled(cron = "${notifications.engagement.cron:0 20 * * * *}", zone = "UTC")
    public void run() {
        if (!enabled) return;
        if (sinceAwayEnabled) {
            sendSinceAwayHighlights();
        }
        if (trendingEnabled) {
            sendTrendingToday();
        }
    }

    private void sendSinceAwayHighlights() {
        long cursorUserId = 0L;
        while (true) {
            var rows = engagement.listSinceAwayCandidates(sinceAwayHours, sinceAwayMinNewPosts, cursorUserId, sinceAwayBatchSize);
            if (rows.isEmpty()) break;

            for (var row : rows) {
                notifications.notifySinceAwayHighlights(
                        row.userId(),
                        row.lastAppOpenAt(),
                        row.newPostsCount()
                );
            }

            cursorUserId = rows.get(rows.size() - 1).userId();
            if (rows.size() < sinceAwayBatchSize) break;
        }
    }

    private void sendTrendingToday() {
        long cursorUserId = 0L;
        int processed = 0;
        while (processed < trendingMaxUsersPerRun) {
            int remaining = trendingMaxUsersPerRun - processed;
            int batch = Math.max(1, Math.min(trendingBatchSize, remaining));
            var rows = engagement.listTrendingCandidates(trendingAwayHours, cursorUserId, batch);
            if (rows.isEmpty()) break;

            for (var row : rows) {
                processed += 1;
                if (row.firebaseUid() == null || row.firebaseUid().isBlank()) continue;
                PostRepository.TrendingRow trending = topTrending(row);
                if (trending == null) continue;

                int engagements = Math.max(0, trending.likesCount)
                        + Math.max(0, trending.commentsCount)
                        + Math.max(0, trending.shareCount)
                        + Math.max(0, trending.repostCount);
                if (engagements < trendingMinEngagements) continue;

                Long communityId = trending.communityId != null ? trending.communityId : row.displayCommunityId();
                notifications.notifyTrendingToday(
                        row.userId(),
                        trending.id,
                        communityId,
                        trending.communityName,
                        trending.score,
                        engagements
                );
            }

            cursorUserId = rows.get(rows.size() - 1).userId();
            if (rows.size() < batch) break;
        }
    }

    private PostRepository.TrendingRow topTrending(NotificationEngagementRepository.TrendingCandidate row) {
        var scoped = feed.trending(row.firebaseUid(), 1, row.displayCommunityId());
        if (scoped.status() == FeedService.Status.OK && scoped.items() != null && !scoped.items().isEmpty()) {
            return scoped.items().get(0);
        }
        if (scoped.status() == FeedService.Status.COMMUNITY_NOT_FOUND || scoped.status() == FeedService.Status.COMMUNITY_BANNED) {
            var fallback = feed.trending(row.firebaseUid(), 1, null);
            if (fallback.status() == FeedService.Status.OK && fallback.items() != null && !fallback.items().isEmpty()) {
                return fallback.items().get(0);
            }
        }
        return null;
    }
}
