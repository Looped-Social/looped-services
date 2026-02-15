package com.looped.notifications;

import com.looped.communities.CommunityVerificationReminderJob;
import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class VerificationNotificationsTest extends PostgresTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NotificationPublisher publisher;

    @Autowired
    CommunityVerificationReminderJob reminderJob;

    @Test
    void verification_approved_notifications_use_announcement_shape() {
        long userId = jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES ('uid-vn-approved','vn-approved',1) RETURNING id",
                Long.class
        );

        publisher.notifyCommunityVerificationApproved(
                userId,
                1L,
                "Looped Global",
                "email",
                77L,
                java.time.OffsetDateTime.now().plusDays(30)
        );

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ?",
                Integer.class,
                userId
        );
        assertThat(total).isEqualTo(1);

        String type = jdbc.queryForObject(
                "SELECT type FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class,
                userId
        );
        assertThat(type).isEqualTo("announcement");

        String kind = jdbc.queryForObject(
                "SELECT payload->>'kind' FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class,
                userId
        );
        String title = jdbc.queryForObject(
                "SELECT payload->>'title' FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class,
                userId
        );
        String actionDeeplink = jdbc.queryForObject(
                "SELECT payload->>'action_deeplink' FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class,
                userId
        );
        assertThat(kind).isEqualTo("community_verification");
        assertThat(title).isEqualTo("Verified");
        assertThat(actionDeeplink).startsWith("looped://announcement/");
    }

    @Test
    void reminder_job_sends_7_day_1_day_and_expired_notifications_once() {
        long communityId = jdbc.queryForObject(
                "INSERT INTO communities(kind, name) VALUES ('company', 'Reminder Co') RETURNING id",
                Long.class
        );

        long user7 = insertUser("uid-vn-7", "vn7");
        long user1 = insertUser("uid-vn-1", "vn1");
        long userExpired = insertUser("uid-vn-exp", "vnexp");

        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) VALUES (?,?,?,?, now(), now() + interval '7 days')",
                user7, communityId, "email", true
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) VALUES (?,?,?,?, now(), now() + interval '1 day')",
                user1, communityId, "thirdparty", true
        );
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) VALUES (?,?,?,?, now() - interval '10 days', now() - interval '1 hour')",
                userExpired, communityId, "photo_id", true
        );

        reminderJob.run();
        reminderJob.run();

        Integer count7 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'announcement' " +
                        "AND payload->>'kind' = 'community_verification' " +
                        "AND payload->>'status' = 'expiring' " +
                        "AND payload->>'days_remaining' = '7'",
                Integer.class,
                user7
        );
        Integer count1 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'announcement' " +
                        "AND payload->>'kind' = 'community_verification' " +
                        "AND payload->>'status' = 'expiring' " +
                        "AND payload->>'days_remaining' = '1'",
                Integer.class,
                user1
        );
        Integer countExpired = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'announcement' " +
                        "AND payload->>'kind' = 'community_verification' " +
                        "AND payload->>'status' = 'expired'",
                Integer.class,
                userExpired
        );

        assertThat(count7).isEqualTo(1);
        assertThat(count1).isEqualTo(1);
        assertThat(countExpired).isEqualTo(1);

        List<String> titles = jdbc.queryForList(
                "SELECT payload->>'title' FROM notifications WHERE user_id IN (?,?,?) ORDER BY id",
                String.class,
                user7, user1, userExpired
        );
        assertThat(titles).contains("Verification expiring soon", "Verification expired");
    }

    private long insertUser(String uid, String handle) {
        return jdbc.queryForObject(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?,?,1) RETURNING id",
                Long.class,
                uid, handle
        );
    }
}
