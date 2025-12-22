-- Admin invite flow and permissions

ALTER TABLE IF EXISTS admin_users
    ADD COLUMN IF NOT EXISTS permissions TEXT[] NOT NULL DEFAULT '{}'::text[];

CREATE TABLE IF NOT EXISTS admin_invites (
    id BIGSERIAL PRIMARY KEY,
    email TEXT NOT NULL,
    role TEXT NOT NULL,
    permissions TEXT[] NOT NULL DEFAULT '{}'::text[],
    token_hash TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'pending',
    created_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    accepted_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    CHECK (email = lower(email))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_admin_invites_token_hash ON admin_invites (token_hash);
CREATE UNIQUE INDEX IF NOT EXISTS idx_admin_invites_email_pending ON admin_invites (lower(email)) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_admin_invites_status ON admin_invites (status);

UPDATE admin_users
SET permissions = ARRAY[
    'manage_admins','ban_user','remove_post','create_community',
    'view_reports','resolve_reports','verify_users','delete_media','view_feedback'
]::text[]
WHERE role = 'owner' AND permissions = '{}'::text[];

UPDATE admin_users
SET permissions = ARRAY[
    'ban_user','remove_post','create_community',
    'view_reports','resolve_reports','verify_users','delete_media','view_feedback'
]::text[]
WHERE role = 'admin' AND permissions = '{}'::text[];

UPDATE admin_users
SET permissions = ARRAY[
    'ban_user','remove_post','create_community',
    'view_reports','resolve_reports','view_feedback'
]::text[]
WHERE role = 'moderator' AND permissions = '{}'::text[];
