package com.looped.notifications;

import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CelebrationNotificationsTest extends PostgresTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NotificationPublisher publisher;

    @Autowired
    CelebrationsRepository celebrations;

    @Test
    void notifyAnnouncementOnce_is_idempotent_per_user_event_key() {
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-bday','bday',1) RETURNING id",
                Long.class
        );

        Map<String, Object> payload = Map.of(
                "title", "Happy birthday!",
                "body", "Happy birthday from Looped."
        );

        int created1 = publisher.notifyAnnouncementOnce(List.of(userId), "birthday-2026", payload);
        int created2 = publisher.notifyAnnouncementOnce(List.of(userId), "birthday-2026", payload);
        int created3 = publisher.notifyAnnouncementOnce(List.of(userId), "birthday-2027", payload);

        assertThat(created1).isEqualTo(1);
        assertThat(created2).isEqualTo(0);
        assertThat(created3).isEqualTo(1);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'announcement'",
                Integer.class,
                userId
        );
        assertThat(count).isEqualTo(2);

        List<String> keys = jdbc.queryForList(
                "SELECT payload->>'event_key' FROM notifications WHERE user_id = ? AND type = 'announcement' ORDER BY id",
                String.class,
                userId
        );
        assertThat(keys).containsExactly("birthday-2026", "birthday-2027");
    }

    @Test
    void celebrationsRepository_lists_birthdays_and_anniversaries() {
        long birthday28 = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-b28','b28',1) RETURNING id",
                Long.class
        );
        long birthday29 = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-b29','b29',1) RETURNING id",
                Long.class
        );
        jdbc.update("UPDATE users SET date_of_birth = DATE '1990-02-28' WHERE id = ?", birthday28);
        jdbc.update("UPDATE users SET date_of_birth = DATE '1992-02-29' WHERE id = ?", birthday29);

        List<Long> ids28Only = celebrations.listBirthdayUserIds(2, new int[]{28}, 0L, 50);
        assertThat(ids28Only).contains(birthday28).doesNotContain(birthday29);

        List<Long> ids28And29 = celebrations.listBirthdayUserIds(2, new int[]{28, 29}, 0L, 50);
        assertThat(ids28And29).contains(birthday28, birthday29);

        long ann1 = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, created_at) VALUES ('uid-ann1','ann1',1,'2025-02-03T12:00:00Z') RETURNING id",
                Long.class
        );
        long ann2 = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, created_at) VALUES ('uid-ann2','ann2',1,'2024-02-03T12:00:00Z') RETURNING id",
                Long.class
        );
        long ann0 = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id, created_at) VALUES ('uid-ann0','ann0',1,'2026-02-03T12:00:00Z') RETURNING id",
                Long.class
        );

        LocalDate today = LocalDate.parse("2026-02-03");
        List<CelebrationsRepository.AnniversaryCandidate> anniversaries =
                celebrations.listAnniversaryCandidates(today, 2, new int[]{3}, 0L, 50);

        assertThat(anniversaries).contains(
                new CelebrationsRepository.AnniversaryCandidate(ann1, 1),
                new CelebrationsRepository.AnniversaryCandidate(ann2, 2)
        );
        assertThat(anniversaries).doesNotContain(new CelebrationsRepository.AnniversaryCandidate(ann0, 0));
    }
}

