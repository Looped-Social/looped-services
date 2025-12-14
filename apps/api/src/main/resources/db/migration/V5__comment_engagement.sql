-- Comment engagement (likes) and counts

ALTER TABLE IF EXISTS comments
    ADD COLUMN IF NOT EXISTS likes_count INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS comment_likes (
    id          BIGSERIAL PRIMARY KEY,
    comment_id  BIGINT NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (comment_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_comment_likes_comment_user ON comment_likes(comment_id, user_id);
CREATE INDEX IF NOT EXISTS idx_comment_likes_user_created ON comment_likes(user_id, created_at DESC, id DESC);
