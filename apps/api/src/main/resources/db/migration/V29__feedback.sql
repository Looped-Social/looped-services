-- User feedback submissions

CREATE TABLE IF NOT EXISTS feedback (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    email TEXT,
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'open',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    reviewed_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    reviewed_note TEXT,
    CHECK (email IS NULL OR email = lower(email))
);

CREATE INDEX IF NOT EXISTS idx_feedback_created_at ON feedback(created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_feedback_status ON feedback(status);
