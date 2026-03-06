-- Generic targeted one-time notice state per user.
-- Initial rollout notice key:
--   workplace_fields_migration_v1

CREATE TABLE IF NOT EXISTS user_notice_state (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notice_key TEXT NOT NULL,
    eligible BOOLEAN NOT NULL DEFAULT false,
    first_eligible_at TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    ack_action TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, notice_key),
    CONSTRAINT user_notice_state_ack_action_check
        CHECK (ack_action IS NULL OR ack_action IN ('dismiss', 'cta')),
    CONSTRAINT user_notice_state_first_eligible_required
        CHECK (NOT eligible OR first_eligible_at IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_user_notice_state_user_pending
    ON user_notice_state(user_id, notice_key)
    WHERE eligible = true AND acknowledged_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_user_notice_state_notice_eligible
    ON user_notice_state(notice_key, user_id)
    WHERE eligible = true;

-- Backfill users impacted by school/major deprecation using durable post-migration signals.
-- This is intentionally conservative to avoid false positives.
WITH impact_signals AS (
    SELECT
        usl.user_id,
        usl.last_changed_at AS signal_at
    FROM user_specialization_limits usl
    WHERE lower(COALESCE(usl.specialization_type, '')) = 'major'

    UNION ALL

    SELECT
        ov.user_id,
        COALESCE(ov.org_selected_at, ov.updated_at) AS signal_at
    FROM user_onboarding_v2 ov
    WHERE ov.org_selected_at IS NOT NULL
      AND ov.selected_org_id IS NULL
      AND ov.selected_org_kind IS NULL

    UNION ALL

    SELECT
        ov.user_id,
        COALESCE(ov.specialization_selected_at, ov.updated_at) AS signal_at
    FROM user_onboarding_v2 ov
    WHERE ov.specialization_selected_at IS NOT NULL
      AND ov.selected_specialization_id IS NULL
),
impacted_users AS (
    SELECT
        s.user_id,
        MIN(s.signal_at) AS first_signal_at
    FROM impact_signals s
    GROUP BY s.user_id
)
INSERT INTO user_notice_state (
    user_id,
    notice_key,
    eligible,
    first_eligible_at,
    created_at,
    updated_at
)
SELECT
    iu.user_id,
    'workplace_fields_migration_v1',
    true,
    COALESCE(iu.first_signal_at, now()),
    now(),
    now()
FROM impacted_users iu
JOIN users u ON u.id = iu.user_id
WHERE u.deleted_at IS NULL
ON CONFLICT (user_id, notice_key) DO UPDATE
SET eligible = true,
    first_eligible_at = COALESCE(user_notice_state.first_eligible_at, EXCLUDED.first_eligible_at),
    updated_at = now();
