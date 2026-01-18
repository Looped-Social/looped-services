package com.looped.communities;

import com.looped.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CommunityVerificationEmailUniquenessTest extends PostgresTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CommunityVerificationsRepository verifications;

    @Test
    void email_cannot_be_verified_twice_before_expiry() {
        long communityId = createCommunity("school", "UNC");
        long user1 = createUser("firebase-1", "u1", 1L);
        long user2 = createUser("firebase-2", "u2", 1L);

        String email = "wvmillen@unc.edu";
        verifications.markVerified(user1, communityId, "email", OffsetDateTime.now().plusDays(365), email);

        assertThatThrownBy(() -> verifications.markVerified(user2, communityId, "email", OffsetDateTime.now().plusDays(365), email))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void email_can_be_used_again_after_expiry_cleanup() {
        long communityId = createCommunity("school", "UNC");
        long user1 = createUser("firebase-1", "u1", 1L);
        long user2 = createUser("firebase-2", "u2", 1L);

        String email = "wvmillen@unc.edu";
        verifications.markVerified(user1, communityId, "email", OffsetDateTime.now().minusDays(1), email);

        verifications.expireAllExpiredNow();

        verifications.markVerified(user2, communityId, "email", OffsetDateTime.now().plusDays(365), email);
        assertThat(verifications.findActiveOwnerUserId(communityId, email)).contains(user2);
    }

    @Test
    void unlink_releases_email_for_other_accounts() {
        long communityId = createCommunity("school", "UNC");
        long user1 = createUser("firebase-1", "u1", 1L);
        long user2 = createUser("firebase-2", "u2", 1L);

        String email = "wvmillen@unc.edu";
        verifications.markVerified(user1, communityId, "email", OffsetDateTime.now().plusDays(365), email);

        verifications.unverifyAndReleaseEmail(user1, communityId);

        verifications.markVerified(user2, communityId, "email", OffsetDateTime.now().plusDays(365), email);
        assertThat(verifications.findActiveOwnerUserId(communityId, email)).contains(user2);
    }

    private long createCommunity(String kind, String name) {
        Long id = jdbc.query(
                "INSERT INTO communities(kind, name) VALUES (?, ?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                kind, name
        );
        if (id == null) throw new IllegalStateException("failed to create community");
        return id;
    }

    private long createUser(String firebaseUid, String handle, long companyId) {
        Long id = jdbc.query(
                "INSERT INTO users(firebase_uid, handle, company_id) VALUES (?, ?, ?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                firebaseUid, handle, companyId
        );
        if (id == null) throw new IllegalStateException("failed to create user");
        return id;
    }
}

