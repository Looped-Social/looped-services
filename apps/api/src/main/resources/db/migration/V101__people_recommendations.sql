CREATE TABLE IF NOT EXISTS people_reco_served_audit (
    id BIGSERIAL PRIMARY KEY,
    request_id UUID NOT NULL,
    viewer_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    candidate_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    rail TEXT NOT NULL,
    surface TEXT NOT NULL,
    recommendation_id TEXT NOT NULL,
    tracking_token TEXT NOT NULL,
    reason_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    reason_texts JSONB NOT NULL DEFAULT '[]'::jsonb,
    rank_score BIGINT NOT NULL,
    position INT NOT NULL,
    model_version TEXT NOT NULL,
    experiment_key TEXT,
    experiment_bucket TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT people_reco_served_audit_rail_check
        CHECK (rail IN ('pymk', 'community', 'active_community')),
    CONSTRAINT people_reco_served_audit_position_check
        CHECK (position > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_people_reco_served_tracking_token
    ON people_reco_served_audit(tracking_token);
CREATE INDEX IF NOT EXISTS idx_people_reco_served_viewer_created
    ON people_reco_served_audit(viewer_user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_people_reco_served_viewer_candidate_created
    ON people_reco_served_audit(viewer_user_id, candidate_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_people_reco_served_request
    ON people_reco_served_audit(request_id);

CREATE TABLE IF NOT EXISTS people_reco_feedback_events (
    id BIGSERIAL PRIMARY KEY,
    event_id TEXT NOT NULL,
    viewer_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    candidate_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    recommendation_id TEXT,
    tracking_token TEXT,
    rail TEXT,
    surface TEXT,
    event_type TEXT NOT NULL,
    position INT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    client_ts TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT people_reco_feedback_events_type_check
        CHECK (event_type IN ('impression', 'profile_open', 'connect_request_sent', 'connect_accepted', 'hide', 'less_like_this')),
    CONSTRAINT people_reco_feedback_events_rail_check
        CHECK (rail IS NULL OR rail IN ('pymk', 'community', 'active_community'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_people_reco_feedback_event_id
    ON people_reco_feedback_events(event_id);
CREATE INDEX IF NOT EXISTS idx_people_reco_feedback_viewer_created
    ON people_reco_feedback_events(viewer_user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_people_reco_feedback_candidate_created
    ON people_reco_feedback_events(candidate_user_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS people_reco_suppressions (
    viewer_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    candidate_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    suppression_type TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (viewer_user_id, candidate_user_id, suppression_type),
    CONSTRAINT people_reco_suppressions_type_check
        CHECK (suppression_type IN ('hide', 'less_like_this'))
);

CREATE INDEX IF NOT EXISTS idx_people_reco_suppressions_viewer_expires
    ON people_reco_suppressions(viewer_user_id, expires_at DESC);
CREATE INDEX IF NOT EXISTS idx_people_reco_suppressions_candidate_expires
    ON people_reco_suppressions(candidate_user_id, expires_at DESC);
