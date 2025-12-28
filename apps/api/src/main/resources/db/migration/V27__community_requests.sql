-- Community requests (user-submitted, admin-reviewed)

CREATE TABLE IF NOT EXISTS community_requests (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    image_key TEXT,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    reviewed_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    reject_reason TEXT,
    community_id BIGINT REFERENCES communities(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_community_requests_status ON community_requests(status);
CREATE INDEX IF NOT EXISTS idx_community_requests_created_at ON community_requests(created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_community_requests_user_id ON community_requests(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_community_requests_pending_unique
    ON community_requests(user_id, kind, lower(name))
    WHERE status = 'pending';
