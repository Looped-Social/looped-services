-- Admin moderation primitives (verification queue, reports, bans, audit, post removal)

ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS email TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'users_email_lower'
    ) THEN
        ALTER TABLE users
            ADD CONSTRAINT users_email_lower CHECK (email IS NULL OR email = lower(email));
    END IF;
END$$;

CREATE INDEX IF NOT EXISTS idx_users_email_lower ON users (lower(email)) WHERE email IS NOT NULL;

ALTER TABLE IF EXISTS posts
    ADD COLUMN IF NOT EXISTS removed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS removed_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS removed_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_posts_removed_at ON posts (removed_at);

ALTER TABLE IF EXISTS reports
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolved_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS resolved_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_reports_status ON reports (status);

CREATE TABLE IF NOT EXISTS verification_requests (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email TEXT,
    method TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    media_key TEXT,
    metadata TEXT,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    reviewed_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    reject_reason TEXT,
    CHECK (email IS NULL OR email = lower(email))
);

CREATE INDEX IF NOT EXISTS idx_verification_requests_status ON verification_requests (status);
CREATE INDEX IF NOT EXISTS idx_verification_requests_submitted_at ON verification_requests (submitted_at);

CREATE TABLE IF NOT EXISTS user_bans (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    created_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_user_bans_user_id ON user_bans (user_id);
CREATE INDEX IF NOT EXISTS idx_user_bans_active ON user_bans (user_id, revoked_at, expires_at);

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor_admin_id BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    action TEXT NOT NULL,
    target_type TEXT,
    target_id BIGINT,
    meta TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_created_at ON admin_audit_log (created_at DESC);
