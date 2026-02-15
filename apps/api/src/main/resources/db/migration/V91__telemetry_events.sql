-- Telemetry event ingestion (impressions + view funnel)

CREATE TABLE IF NOT EXISTS telemetry_events (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    principal_id BIGINT NOT NULL REFERENCES principals(id) ON DELETE CASCADE,

    session_id UUID NOT NULL,
    event_id UUID NOT NULL,
    type TEXT NOT NULL,

    occurred_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    post_id BIGINT,
    comment_id BIGINT,
    community_id BIGINT,

    feed_mode TEXT,
    feed_community_id BIGINT,
    feed_request_id UUID,
    feed_position INT,

    payload JSONB NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT telemetry_events_event_id_unique UNIQUE (event_id)
);

CREATE INDEX IF NOT EXISTS idx_telemetry_events_type_occurred
    ON telemetry_events(type, occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_telemetry_events_user_occurred
    ON telemetry_events(user_id, occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_telemetry_events_post_occurred
    ON telemetry_events(post_id, occurred_at DESC, id DESC)
    WHERE post_id IS NOT NULL;

