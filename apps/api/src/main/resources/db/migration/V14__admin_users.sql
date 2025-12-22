-- Admin allowlist for dashboard access

CREATE TABLE IF NOT EXISTS admin_users (
    id BIGSERIAL PRIMARY KEY,
    firebase_uid TEXT UNIQUE,
    email TEXT,
    role TEXT NOT NULL DEFAULT 'admin',
    status TEXT NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ,
    CHECK (email IS NULL OR email = lower(email))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_admin_users_email_lower ON admin_users (lower(email)) WHERE email IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_admin_users_status ON admin_users (status);
