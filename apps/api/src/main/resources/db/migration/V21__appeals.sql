-- Appeals for bans and post removals

CREATE TABLE IF NOT EXISTS appeals (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_type TEXT NOT NULL,
    target_id BIGINT NOT NULL,
    reason TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'open',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    reviewed_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    reviewed_reason TEXT
);

CREATE INDEX IF NOT EXISTS idx_appeals_user_id ON appeals (user_id);
CREATE INDEX IF NOT EXISTS idx_appeals_status ON appeals (status);
CREATE INDEX IF NOT EXISTS idx_appeals_created_at ON appeals (created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_appeals_open_unique
    ON appeals (user_id, target_type, target_id)
    WHERE status = 'open';
