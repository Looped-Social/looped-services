package com.looped.principals;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PrincipalStatsRepository {
    private final JdbcTemplate jdbc;

    public PrincipalStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int countFollowers(long principalId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM principal_follows WHERE followee_principal_id = ?",
                Integer.class, principalId
        );
        return count == null ? 0 : count;
    }

    public int countFollowing(long principalId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM principal_follows WHERE follower_principal_id = ?",
                Integer.class, principalId
        );
        return count == null ? 0 : count;
    }

    public int countPosts(long principalId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE author_principal_id = ?",
                Integer.class, principalId
        );
        return count == null ? 0 : count;
    }
}
