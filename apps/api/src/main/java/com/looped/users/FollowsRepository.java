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
}
