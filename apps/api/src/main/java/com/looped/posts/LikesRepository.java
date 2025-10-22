package com.looped.posts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LikesRepository {
    private final JdbcTemplate jdbc;

    public LikesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfAbsent(long userId, long postId) {
        int rows = jdbc.update(
                "INSERT INTO likes(user_id, post_id) VALUES (?,?) ON CONFLICT (user_id, post_id) DO NOTHING",
                userId, postId
        );
        return rows > 0;
    }

    public void incrementPostLikes(long postId) {
        jdbc.update("UPDATE posts SET likes_count = likes_count + 1 WHERE id=?", postId);
    }
}
