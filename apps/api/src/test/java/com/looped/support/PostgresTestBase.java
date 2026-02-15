package com.looped.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.junit.jupiter.api.BeforeEach;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgresTestBase {
    @Container
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void cleanDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            // Truncate all app tables and reset identities between tests to avoid cross-test interference
            s.execute("TRUNCATE TABLE conversation_messages, conversation_message_requests, channel_messages, notifications, comment_likes, comments, " +
                    "telemetry_events, " +
                    "principal_follows, principal_blocks, principal_saved_posts, post_likes, post_shares, post_reposts, follows, saved_posts, likes, principal_settings, " +
                    "conversation_participants, conversations, channel_members, channels, posts, devices, reports, media_assets, " +
                    "poll_vote_options, poll_votes, poll_options, polls, " +
                    "anon_enrollment_sanctions, anon_backup_blobs, anon_revocations, anon_issuers, anon_handle_counters, anonymous_profiles, " +
                    "principals, verifications, hashtag_posts, hashtags, community_verifications, community_follows, specialization_joins, user_specialization_limits, " +
                    "community_requests, community_domains, feedback, communities, user_loops, loops, admin_announcements, admin_invites, admin_users, app_settings, " +
                    "user_community_bans, user_share_slugs, user_tombstones, users, companies " +
                    "RESTART IDENTITY CASCADE");
            s.execute("INSERT INTO companies(name, domain) VALUES ('Looped Global','looped.global')");
        }
    }
}
