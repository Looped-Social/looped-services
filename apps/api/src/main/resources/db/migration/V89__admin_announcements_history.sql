-- Persist one row per admin announcement send for dashboard history.

CREATE TABLE IF NOT EXISTS admin_announcements (
    id BIGSERIAL PRIMARY KEY,
    actor_admin_id BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    scope TEXT NOT NULL,
    company_id BIGINT,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    deeplink TEXT,
    sent_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (scope IN ('company', 'global')),
    CHECK (
        (scope = 'global' AND company_id IS NULL)
        OR (scope = 'company' AND company_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_admin_announcements_created_at
    ON admin_announcements(created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_admin_announcements_scope_company_created_at
    ON admin_announcements(scope, company_id, created_at DESC, id DESC);
