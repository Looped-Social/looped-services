-- Onboarding v2 state machine context + milestones.
-- This is additive and preserves legacy onboarding_step compatibility.

CREATE TABLE IF NOT EXISTS user_onboarding_v2 (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    stage_v2 TEXT NOT NULL DEFAULT 'profile_setup',
    selected_org_id BIGINT REFERENCES communities(id) ON DELETE SET NULL,
    selected_org_kind TEXT,
    verification_path TEXT,
    verification_status TEXT NOT NULL DEFAULT 'none',
    requires_specialization_selection BOOLEAN NOT NULL DEFAULT false,
    selected_specialization_id BIGINT REFERENCES communities(id) ON DELETE SET NULL,
    completion_reason TEXT,
    info_screen_viewed_at TIMESTAMPTZ,
    org_selected_at TIMESTAMPTZ,
    verification_choice_set_at TIMESTAMPTZ,
    email_verified_at TIMESTAMPTZ,
    specialization_selected_at TIMESTAMPTZ,
    skip_explainer_ack_at TIMESTAMPTZ,
    photo_pending_explainer_ack_at TIMESTAMPTZ,
    finalized_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_user_onboarding_v2_stage CHECK (stage_v2 IN (
        'profile_setup',
        'posting_info',
        'org_selected',
        'email_verification',
        'specialization_selection',
        'skip_explainer',
        'photo_id_verification',
        'photo_pending_explainer',
        'completed'
    )),
    CONSTRAINT chk_user_onboarding_v2_org_kind CHECK (
        selected_org_kind IS NULL OR selected_org_kind IN ('company', 'school')
    ),
    CONSTRAINT chk_user_onboarding_v2_verification_path CHECK (
        verification_path IS NULL OR verification_path IN ('skip', 'email', 'photo_id')
    ),
    CONSTRAINT chk_user_onboarding_v2_verification_status CHECK (
        verification_status IN ('none', 'pending', 'approved', 'rejected')
    ),
    CONSTRAINT chk_user_onboarding_v2_completion_reason CHECK (
        completion_reason IS NULL OR completion_reason IN (
            'skipped_verification',
            'email_verified_and_joined',
            'photo_pending',
            'legacy_backfill'
        )
    )
);

CREATE INDEX IF NOT EXISTS idx_user_onboarding_v2_stage ON user_onboarding_v2(stage_v2);
CREATE INDEX IF NOT EXISTS idx_user_onboarding_v2_selected_org ON user_onboarding_v2(selected_org_id);

-- Backfill all users so onboarding v2 resume is deterministic for existing accounts.
INSERT INTO user_onboarding_v2 (
    user_id,
    stage_v2,
    selected_org_id,
    selected_org_kind,
    verification_status,
    completion_reason,
    finalized_at,
    updated_at
)
SELECT
    u.id,
    CASE
        WHEN u.onboarding_completed_at IS NOT NULL
            OR LOWER(COALESCE(u.onboarding_step, '')) = 'verification_notifications'
            THEN 'completed'
        WHEN LOWER(COALESCE(u.onboarding_step, '')) = 'select_company'
            THEN 'org_selected'
        WHEN LOWER(COALESCE(u.onboarding_step, '')) = 'verification'
            THEN 'email_verification'
        ELSE 'profile_setup'
    END,
    CASE WHEN c.kind IN ('company', 'school') THEN c.id ELSE NULL END,
    CASE WHEN c.kind IN ('company', 'school') THEN c.kind ELSE NULL END,
    'none',
    CASE
        WHEN u.onboarding_completed_at IS NOT NULL
            OR LOWER(COALESCE(u.onboarding_step, '')) = 'verification_notifications'
            THEN 'legacy_backfill'
        ELSE NULL
    END,
    u.onboarding_completed_at,
    now()
FROM users u
LEFT JOIN communities c ON c.id = u.display_community_id
ON CONFLICT (user_id) DO NOTHING;
