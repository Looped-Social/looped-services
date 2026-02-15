package com.looped.communities;

import com.looped.notifications.NotificationPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CommunityVerificationReminderJob {
    private final CommunityVerificationsRepository verifications;
    private final NotificationPublisher notifications;
    private final boolean enabled;
    private final int batchSize;
    private final int expiredLookbackDays;

    public CommunityVerificationReminderJob(CommunityVerificationsRepository verifications,
                                            NotificationPublisher notifications,
                                            @Value("${communities.verification-reminders.enabled:true}") boolean enabled,
                                            @Value("${communities.verification-reminders.batch-size:500}") int batchSize,
                                            @Value("${communities.verification-reminders.expired-lookback-days:14}") int expiredLookbackDays) {
        this.verifications = verifications;
        this.notifications = notifications;
        this.enabled = enabled;
        this.batchSize = Math.max(50, Math.min(batchSize, 5_000));
        this.expiredLookbackDays = Math.max(1, Math.min(expiredLookbackDays, 60));
    }

    @Scheduled(cron = "${communities.verification-reminders.cron:0 10 * * * *}", zone = "UTC")
    public void run() {
        if (!enabled) return;
        sendExpiringSoon(7);
        sendExpiringSoon(1);
        sendExpired();
    }

    private void sendExpiringSoon(int daysRemaining) {
        long cursorUserId = 0L;
        long cursorCommunityId = 0L;
        while (true) {
            var rows = verifications.listActiveExpiringInDays(daysRemaining, cursorUserId, cursorCommunityId, batchSize);
            if (rows.isEmpty()) break;
            for (var row : rows) {
                notifications.notifyCommunityVerificationExpiringSoon(
                        row.userId(),
                        row.communityId(),
                        row.communityName(),
                        row.method(),
                        row.expiresAt(),
                        daysRemaining
                );
            }
            var last = rows.get(rows.size() - 1);
            cursorUserId = last.userId();
            cursorCommunityId = last.communityId();
            if (rows.size() < batchSize) break;
        }
    }

    private void sendExpired() {
        long cursorUserId = 0L;
        long cursorCommunityId = 0L;
        while (true) {
            var rows = verifications.listRecentlyExpiredForReminder(expiredLookbackDays, cursorUserId, cursorCommunityId, batchSize);
            if (rows.isEmpty()) break;
            for (var row : rows) {
                notifications.notifyCommunityVerificationExpired(
                        row.userId(),
                        row.communityId(),
                        row.communityName(),
                        row.method(),
                        row.expiresAt()
                );
            }
            var last = rows.get(rows.size() - 1);
            cursorUserId = last.userId();
            cursorCommunityId = last.communityId();
            if (rows.size() < batchSize) break;
        }
    }
}
