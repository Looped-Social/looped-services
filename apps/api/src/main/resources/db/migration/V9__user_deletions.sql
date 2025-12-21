-- User deletion support

ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deleted_by BIGINT;

CREATE INDEX IF NOT EXISTS idx_users_deleted_at ON users(deleted_at);
