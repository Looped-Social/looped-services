package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CommunityVerificationsRepository {
    private final JdbcTemplate jdbc;

    public CommunityVerificationsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isVerified(long userId, long communityId) {
        Boolean verified = jdbc.query(
                "SELECT verified FROM community_verifications WHERE user_id=? AND community_id=?",
                rs -> rs.next() ? rs.getBoolean("verified") : null,
                userId, communityId
        );
        return Boolean.TRUE.equals(verified);
    }
}
