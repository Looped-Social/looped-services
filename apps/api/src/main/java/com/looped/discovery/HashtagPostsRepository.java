package com.looped.discovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HashtagPostsRepository {
    private final JdbcTemplate jdbc;

    public HashtagPostsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void attach(long hashtagId, long postId) {
        jdbc.update(
                "INSERT INTO hashtag_posts(hashtag_id, post_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                hashtagId, postId
        );
    }

    public void deleteByPostId(long postId) {
        jdbc.update("DELETE FROM hashtag_posts WHERE post_id = ?", postId);
    }
}
