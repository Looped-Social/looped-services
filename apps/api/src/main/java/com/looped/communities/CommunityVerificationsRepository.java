package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class CommunityVerificationsRepository {
    private final JdbcTemplate jdbc;

    public CommunityVerificationsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isVerified(long userId, long communityId) {
        var rows = jdbc.query(
                "SELECT verified, expires_at FROM community_verifications WHERE user_id=? AND community_id=?",
                rs -> {
                    if (!rs.next()) return null;
                    boolean verified = rs.getBoolean("verified");
                    OffsetDateTime expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
                    return new VerificationState(verified, expiresAt);
                },
                userId, communityId
        );
        if (rows == null) return false;
        return rows.verified && (rows.expiresAt == null || rows.expiresAt.isAfter(OffsetDateTime.now()));
    }

    public void markVerified(long userId, long communityId, String method, OffsetDateTime expiresAt) {
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) " +
                        "VALUES (?,?,?, true, now(), ?) " +
                        "ON CONFLICT (user_id, community_id) DO UPDATE SET method=EXCLUDED.method, verified=true, " +
                        "verified_at=now(), expires_at=EXCLUDED.expires_at",
                userId, communityId, method, expiresAt
        );
    }

    public void markUnverified(long userId, long communityId, String method) {
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at) " +
                        "VALUES (?,?,?, false, NULL, NULL) " +
                        "ON CONFLICT (user_id, community_id) DO UPDATE SET method=EXCLUDED.method, verified=false, " +
                        "verified_at=NULL, expires_at=NULL",
                userId, communityId, method
        );
    }

    public List<UserVerificationRow> listForUser(long userId) {
        return jdbc.query(
                "SELECT cv.community_id, cv.method, cv.verified, cv.verified_at, cv.expires_at, " +
                        "c.name AS community_name, c.kind AS community_kind " +
                        "FROM community_verifications cv " +
                        "JOIN communities c ON c.id = cv.community_id " +
                        "WHERE cv.user_id = ? " +
                        "ORDER BY cv.verified DESC, cv.verified_at DESC, cv.community_id DESC",
                (rs, rowNum) -> {
                    UserVerificationRow row = new UserVerificationRow();
                    row.communityId = rs.getLong("community_id");
                    row.method = rs.getString("method");
                    row.verified = rs.getBoolean("verified");
                    row.verifiedAt = rs.getObject("verified_at", OffsetDateTime.class);
                    row.expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
                    row.communityName = rs.getString("community_name");
                    row.communityKind = rs.getString("community_kind");
                    return row;
                },
                userId
        );
    }

    private record VerificationState(boolean verified, OffsetDateTime expiresAt) {}

    public static class UserVerificationRow {
        public long communityId;
        public String method;
        public boolean verified;
        public OffsetDateTime verifiedAt;
        public OffsetDateTime expiresAt;
        public String communityName;
        public String communityKind;
    }
}
