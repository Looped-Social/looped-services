package com.looped.posts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostSharesRepository {
    private final JdbcTemplate jdbc;

    public PostSharesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long principalId, long postId) {
        jdbc.update(
                "INSERT INTO post_shares(sharer_principal_id, post_id) VALUES (?,?)",
                principalId, postId
        );
    }

    public void incrementPostShares(long postId) {
        jdbc.update("UPDATE posts SET share_count = share_count + 1 WHERE id=?", postId);
    }
}
