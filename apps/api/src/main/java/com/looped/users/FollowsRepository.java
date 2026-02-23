package com.looped.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FollowsRepository {
    private final JdbcTemplate jdbc;

    public FollowsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfAbsent(long followerPrincipalId, long followeePrincipalId) {
        int rows = jdbc.update(
                "INSERT INTO principal_follows(follower_principal_id, followee_principal_id) VALUES (?, ?) " +
                        "ON CONFLICT (follower_principal_id, followee_principal_id) DO NOTHING",
                followerPrincipalId, followeePrincipalId
        );
        return rows > 0;
    }

    public boolean delete(long followerPrincipalId, long followeePrincipalId) {
        int rows = jdbc.update(
                "DELETE FROM principal_follows WHERE follower_principal_id=? AND followee_principal_id=?",
                followerPrincipalId, followeePrincipalId
        );
        return rows > 0;
    }

    public boolean exists(long followerPrincipalId, long followeePrincipalId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM principal_follows WHERE follower_principal_id = ? AND followee_principal_id = ?)",
                Boolean.class,
                followerPrincipalId, followeePrincipalId
        );
        return Boolean.TRUE.equals(exists);
    }

    public java.util.List<Long> findFollowerUserIds(long followeePrincipalId) {
        return jdbc.query(
                "SELECT u.id " +
                        "FROM principal_follows f " +
                        "JOIN principals p ON p.id = f.follower_principal_id AND p.kind = 'user' " +
                        "JOIN users u ON u.id = p.user_id AND u.deleted_at IS NULL " +
                        "WHERE f.followee_principal_id = ?",
                (rs, rowNum) -> rs.getLong("id"),
                followeePrincipalId
        );
    }

    public java.util.List<Long> findFolloweePrincipalIds(long followerPrincipalId, int limit) {
        int lim = Math.max(1, Math.min(limit, 2000));
        return jdbc.query(
                "SELECT f.followee_principal_id AS id " +
                        "FROM principal_follows f " +
                        "WHERE f.follower_principal_id = ? " +
                        "ORDER BY f.created_at DESC, f.followee_principal_id DESC " +
                        "LIMIT ?",
                (rs, rowNum) -> rs.getLong("id"),
                followerPrincipalId,
                lim
        );
    }
}
