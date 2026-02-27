-- Per-author post share nudge state. Backend is source of truth for eligibility and anti-spam caps.

CREATE TABLE IF NOT EXISTS post_share_nudge_state (
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    eligible_at TIMESTAMPTZ NOT NULL,
    first_served_at TIMESTAMPTZ,
    dismissed_at TIMESTAMPTZ,
    share_tapped_at TIMESTAMPTZ,
    variant TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id),
    CONSTRAINT post_share_nudge_state_served_after_eligible
        CHECK (first_served_at IS NULL OR first_served_at >= eligible_at)
);

CREATE INDEX IF NOT EXISTS idx_post_share_nudge_user_first_served
    ON post_share_nudge_state(user_id, first_served_at DESC)
    WHERE first_served_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_post_share_nudge_eligible_unserved
    ON post_share_nudge_state(eligible_at, post_id, user_id)
    WHERE first_served_at IS NULL;
