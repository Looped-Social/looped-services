-- Community request contact + notification tracking

ALTER TABLE IF EXISTS community_requests
    ADD COLUMN IF NOT EXISTS contact_email TEXT;

ALTER TABLE IF EXISTS community_requests
    ADD COLUMN IF NOT EXISTS notify_when_available BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE IF EXISTS community_requests
    ADD COLUMN IF NOT EXISTS notified_at TIMESTAMPTZ;

ALTER TABLE IF EXISTS community_requests
    ADD COLUMN IF NOT EXISTS notified_community_id BIGINT REFERENCES communities(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_community_requests_notify_pending
    ON community_requests(kind, created_at DESC, id DESC)
    WHERE status = 'pending' AND notify_when_available = true AND notified_at IS NULL;

