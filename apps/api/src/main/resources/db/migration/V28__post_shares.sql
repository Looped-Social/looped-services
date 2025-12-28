-- Post share events (for analytics)

CREATE TABLE IF NOT EXISTS post_shares (
    id BIGSERIAL PRIMARY KEY,
    sharer_principal_id BIGINT NOT NULL REFERENCES principals(id) ON DELETE CASCADE,
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_post_shares_post ON post_shares(post_id);
CREATE INDEX IF NOT EXISTS idx_post_shares_created_at ON post_shares(created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_post_shares_principal ON post_shares(sharer_principal_id);
