-- One-time user milestones (e.g., first_post_ever) used for UX + funnel tracking.

CREATE TABLE IF NOT EXISTS user_milestones (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    milestone_type TEXT NOT NULL,
    awarded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    post_id BIGINT REFERENCES posts(id) ON DELETE SET NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT user_milestones_user_type_unique UNIQUE (user_id, milestone_type)
);

CREATE INDEX IF NOT EXISTS idx_user_milestones_user_awarded
    ON user_milestones(user_id, awarded_at DESC, id DESC);

