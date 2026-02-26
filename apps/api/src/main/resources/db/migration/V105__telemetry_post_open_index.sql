-- Index to support author-facing unique view counts from telemetry post_open events.
CREATE INDEX IF NOT EXISTS idx_telemetry_events_post_open_post_user
    ON telemetry_events(post_id, user_id)
    WHERE type = 'post_open' AND post_id IS NOT NULL;
