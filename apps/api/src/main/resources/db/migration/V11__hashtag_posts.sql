-- Hashtag to post mapping for hashtag feeds

CREATE TABLE IF NOT EXISTS hashtag_posts (
    hashtag_id BIGINT NOT NULL REFERENCES hashtags(id) ON DELETE CASCADE,
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (hashtag_id, post_id)
);

CREATE INDEX IF NOT EXISTS idx_hashtag_posts_post ON hashtag_posts(post_id);
CREATE INDEX IF NOT EXISTS idx_hashtag_posts_hashtag_created ON hashtag_posts(hashtag_id, created_at DESC, post_id DESC);
