-- Onboarding state persisted on the user record.
-- "onboarding_complete" is defined as: user has reached the notifications step
-- (VerificationNotificationsView) regardless of verification completion or skip.

ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS onboarding_step TEXT NOT NULL DEFAULT 'verification',
    ADD COLUMN IF NOT EXISTS onboarding_completed_at TIMESTAMPTZ;

-- Backfill existing users as "complete" to avoid locking out existing accounts when this field is introduced.
UPDATE users
SET onboarding_step = 'verification_notifications',
    onboarding_completed_at = COALESCE(onboarding_completed_at, created_at)
WHERE onboarding_completed_at IS NULL;

