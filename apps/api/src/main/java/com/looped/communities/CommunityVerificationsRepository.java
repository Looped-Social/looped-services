package com.looped.communities;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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

    public boolean hasActiveVerifiedCommunityOfKind(long userId, String communityKind) {
        if (communityKind == null || communityKind.isBlank()) return false;
        String normalizedKind = communityKind.trim().toLowerCase(Locale.ROOT);
        Boolean exists = jdbc.query(
                "SELECT EXISTS (" +
                        "SELECT 1 " +
                        "FROM community_verifications cv " +
                        "JOIN communities c ON c.id = cv.community_id " +
                        "WHERE cv.user_id = ? " +
                        "AND cv.verified = true " +
                        "AND (cv.expires_at IS NULL OR cv.expires_at > now()) " +
                        "AND lower(c.kind) = ?" +
                        ")",
                rs -> rs.next() ? rs.getBoolean(1) : false,
                userId,
                normalizedKind
        );
        return exists != null && exists;
    }

    public int countActiveVerifiedMembers(long communityId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_verifications " +
                        "WHERE community_id = ? AND verified = true AND (expires_at IS NULL OR expires_at > now())",
                Integer.class,
                communityId
        );
        return count == null ? 0 : count;
    }

    public Map<Long, Integer> countActiveVerifiedMembersByCommunityIds(Collection<Long> communityIds) {
        if (communityIds == null || communityIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(communityIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.addAll(communityIds);
        return jdbc.query(
                "SELECT community_id, COUNT(*) AS cnt FROM community_verifications " +
                        "WHERE community_id IN (" + placeholders + ") " +
                        "AND verified = true AND (expires_at IS NULL OR expires_at > now()) " +
                        "GROUP BY community_id",
                rs -> {
                    Map<Long, Integer> out = new HashMap<>();
                    while (rs.next()) {
                        out.put(rs.getLong("community_id"), rs.getInt("cnt"));
                    }
                    return out;
                },
                args.toArray()
        );
    }

    public java.util.Optional<UserVerificationRow> findForUserAndCommunity(long userId, long communityId) {
        var list = jdbc.query(
                "SELECT community_id, method, verified, verified_at, expires_at " +
                        "FROM community_verifications WHERE user_id=? AND community_id=? " +
                        "LIMIT 1",
                (rs, rowNum) -> {
                    UserVerificationRow row = new UserVerificationRow();
                    row.communityId = rs.getLong("community_id");
                    row.method = rs.getString("method");
                    row.verified = rs.getBoolean("verified");
                    row.verifiedAt = rs.getObject("verified_at", OffsetDateTime.class);
                    row.expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
                    return row;
                },
                userId, communityId
        );
        return list.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(list.get(0));
    }

    public void markVerified(long userId, long communityId, String method, OffsetDateTime expiresAt) {
        markVerified(userId, communityId, method, expiresAt, null);
    }

    public void markVerified(long userId, long communityId, String method, OffsetDateTime expiresAt, String email) {
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at, email) " +
                        "VALUES (?,?,?, true, now(), ?, ?) " +
                        "ON CONFLICT (user_id, community_id) DO UPDATE SET method=EXCLUDED.method, verified=true, " +
                        "verified_at=now(), expires_at=EXCLUDED.expires_at, email=EXCLUDED.email",
                userId, communityId, method, expiresAt, email
        );
    }

    public void markUnverified(long userId, long communityId, String method) {
        jdbc.update(
                "INSERT INTO community_verifications(user_id, community_id, method, verified, verified_at, expires_at, email) " +
                        "VALUES (?,?,?, false, NULL, NULL, NULL) " +
                        "ON CONFLICT (user_id, community_id) DO UPDATE SET method=EXCLUDED.method, verified=false, " +
                        "verified_at=NULL, expires_at=NULL, email=NULL",
                userId, communityId, method
        );
    }

    public boolean unverifyAndReleaseEmail(long userId, long communityId) {
        int rows = jdbc.update(
                "UPDATE community_verifications " +
                        "SET verified=false, verified_at=NULL, expires_at=NULL, email=NULL " +
                        "WHERE user_id=? AND community_id=?",
                userId, communityId
        );
        return rows > 0;
    }

    public int expireAllExpiredNow() {
        return jdbc.update(
                "UPDATE community_verifications " +
                        "SET verified=false, email=NULL " +
                        "WHERE verified=true AND expires_at IS NOT NULL AND expires_at <= now()"
        );
    }

    public int expireExpiredForEmailNow(long communityId, String email) {
        return jdbc.update(
                "UPDATE community_verifications " +
                        "SET verified=false, email=NULL " +
                        "WHERE community_id=? AND email=? AND verified=true AND expires_at IS NOT NULL AND expires_at <= now()",
                communityId, email
        );
    }

    public Optional<Long> findActiveOwnerUserId(long communityId, String email) {
        Long userId = jdbc.query(
                "SELECT user_id FROM community_verifications " +
                        "WHERE community_id=? AND email=? AND verified=true AND (expires_at IS NULL OR expires_at > now()) " +
                        "LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null,
                communityId, email
        );
        return Optional.ofNullable(userId);
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
