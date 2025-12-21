-- Loop follows and loop-scoped posts

ALTER TABLE IF EXISTS posts
    ADD COLUMN IF NOT EXISTS loop_id BIGINT REFERENCES loops(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_posts_company_loop_created_at
    ON posts(company_id, loop_id, created_at ASC, id ASC);

CREATE TABLE IF NOT EXISTS user_loops (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    loop_id    BIGINT NOT NULL REFERENCES loops(id) ON DELETE CASCADE,
    is_pinned  BOOLEAN NOT NULL DEFAULT false,
    sort_order INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, loop_id)
);
CREATE INDEX IF NOT EXISTS idx_user_loops_user_created_at
    ON user_loops(user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_user_loops_loop
    ON user_loops(loop_id);
