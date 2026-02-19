-- Per-user community widget state for "new activity since last checked" semantics.

CREATE TABLE IF NOT EXISTS widget_community_state (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    last_seen_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, community_id)
);

CREATE INDEX IF NOT EXISTS idx_widget_community_state_user_seen
    ON widget_community_state(user_id, last_seen_at DESC, community_id DESC);
