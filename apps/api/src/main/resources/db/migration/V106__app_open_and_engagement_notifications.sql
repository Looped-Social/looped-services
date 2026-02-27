-- Track last app open for reminder eligibility and add a candidate queue table for scheduled engagement notifications.

ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS last_app_open_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_users_last_app_open_at
    ON users(last_app_open_at)
    WHERE last_app_open_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS notification_candidates (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type TEXT NOT NULL,
    community_id BIGINT REFERENCES communities(id) ON DELETE SET NULL,
    post_id BIGINT REFERENCES posts(id) ON DELETE SET NULL,
    reason JSONB,
    dedupe_key TEXT NOT NULL,
    deliver_after TIMESTAMPTZ NOT NULL DEFAULT now(),
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    attempt_count INT NOT NULL DEFAULT 0,
    CHECK (status IN ('pending', 'sent', 'failed', 'canceled'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_notification_candidates_user_dedupe
    ON notification_candidates(user_id, dedupe_key);

CREATE INDEX IF NOT EXISTS idx_notification_candidates_status_deliver_after
    ON notification_candidates(status, deliver_after, id);
