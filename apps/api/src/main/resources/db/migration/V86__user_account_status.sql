-- User account disable + admin delete metadata

ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS disabled_reason TEXT,
    ADD COLUMN IF NOT EXISTS disabled_by_admin_id BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS deleted_source TEXT,
    ADD COLUMN IF NOT EXISTS deleted_by_admin_id BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS deleted_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_users_disabled_at ON users(disabled_at);
CREATE INDEX IF NOT EXISTS idx_users_deleted_source ON users(deleted_source) WHERE deleted_source IS NOT NULL;

