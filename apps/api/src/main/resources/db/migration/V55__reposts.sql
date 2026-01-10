-- Post reposts (toggleable, per-principal)

ALTER TABLE IF EXISTS posts
    ADD COLUMN IF NOT EXISTS repost_count INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS post_reposts (
    id BIGSERIAL PRIMARY KEY,
    reposter_principal_id BIGINT NOT NULL REFERENCES principals(id) ON DELETE CASCADE,
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (reposter_principal_id, post_id)
);

CREATE INDEX IF NOT EXISTS idx_post_reposts_post_created_at ON post_reposts(post_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_post_reposts_principal_created_at ON post_reposts(reposter_principal_id, created_at DESC, id DESC);

