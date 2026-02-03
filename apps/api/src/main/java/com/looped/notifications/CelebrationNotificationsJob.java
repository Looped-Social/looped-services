package com.looped.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class CelebrationNotificationsJob {
    private static final Logger log = LoggerFactory.getLogger(CelebrationNotificationsJob.class);
    private final CelebrationsRepository celebrations;
    private final NotificationPublisher notifications;
    private final boolean enabled;
    private final boolean birthdayEnabled;
    private final boolean anniversaryEnabled;
    private final int batchSize;

    public CelebrationNotificationsJob(CelebrationsRepository celebrations,
                                       NotificationPublisher notifications,
                                       @Value("${notifications.celebrations.enabled:true}") boolean enabled,
                                       @Value("${notifications.celebrations.birthday-enabled:true}") boolean birthdayEnabled,
                                       @Value("${notifications.celebrations.anniversary-enabled:true}") boolean anniversaryEnabled,
                                       @Value("${notifications.celebrations.batch-size:500}") int batchSize) {
        this.celebrations = celebrations;
        this.notifications = notifications;
        this.enabled = enabled;
        this.birthdayEnabled = birthdayEnabled;
        this.anniversaryEnabled = anniversaryEnabled;
        this.batchSize = Math.max(50, Math.min(batchSize, 5_000));
    }

    @Scheduled(cron = "${notifications.celebrations.cron:0 0 14 * * *}", zone = "UTC")
    public void run() {
        if (!enabled) return;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int[] days = birthdayOrAnniversaryDays(today);

        int birthdayCreated = birthdayEnabled ? sendBirthdays(today, days) : 0;
        int anniversaryCreated = anniversaryEnabled ? sendAnniversaries(today, days) : 0;

        int total = birthdayCreated + anniversaryCreated;
        if (total > 0) {
            log.info("Celebration notifications created={} birthdaysCreated={} anniversariesCreated={} date={}",
                    total, birthdayCreated, anniversaryCreated, today);
        }
    }

    private int sendBirthdays(LocalDate today, int[] days) {
        String eventKey = "birthday-" + today.getYear();
        Map<String, Object> payload = Map.of(
                "kind", "birthday",
                "title", "Happy birthday!",
                "body", "Happy birthday from Looped."
        );
        int month = today.getMonthValue();

        int created = 0;
        long cursorId = 0L;
        while (true) {
            List<Long> userIds = celebrations.listBirthdayUserIds(month, days, cursorId, batchSize);
            if (userIds.isEmpty()) break;
            created += notifications.notifyAnnouncementOnce(userIds, eventKey, payload);
            cursorId = userIds.get(userIds.size() - 1);
            if (userIds.size() < batchSize) break;
        }
        return created;
    }

    private int sendAnniversaries(LocalDate today, int[] days) {
        int month = today.getMonthValue();
        int created = 0;
        long cursorId = 0L;
        while (true) {
            List<CelebrationsRepository.AnniversaryCandidate> candidates =
                    celebrations.listAnniversaryCandidates(today, month, days, cursorId, batchSize);
            if (candidates.isEmpty()) break;

            Map<Integer, List<Long>> userIdsByYears = new HashMap<>();
            for (var row : candidates) {
                if (row.userId() <= 0 || row.years() <= 0) continue;
                userIdsByYears.computeIfAbsent(row.years(), ignored -> new ArrayList<>()).add(row.userId());
            }
            for (var entry : userIdsByYears.entrySet()) {
                int years = entry.getKey();
                List<Long> userIds = entry.getValue();
                if (userIds == null || userIds.isEmpty()) continue;
                String eventKey = "anniversary-" + years;
                Map<String, Object> payload = Map.of(
                        "kind", "anniversary",
                        "years", years,
                        "title", anniversaryTitle(years),
                        "body", anniversaryBody(years)
                );
                created += notifications.notifyAnnouncementOnce(userIds, eventKey, payload);
            }

            cursorId = candidates.get(candidates.size() - 1).userId();
            if (candidates.size() < batchSize) break;
        }
        return created;
    }

    private static int[] birthdayOrAnniversaryDays(LocalDate today) {
        if (today == null) return new int[] {};
        if (today.getMonthValue() == 2 && today.getDayOfMonth() == 28 && !today.isLeapYear()) {
            return new int[]{28, 29};
        }
        return new int[]{today.getDayOfMonth()};
    }

    private static String anniversaryTitle(int years) {
        if (years <= 1) return "Happy 1-year anniversary!";
        return "Happy " + years + "-year anniversary!";
    }

    private static String anniversaryBody(int years) {
        if (years <= 1) return "Thanks for being on Looped for 1 year.";
        return "Thanks for being on Looped for " + years + " years.";
    }
}

