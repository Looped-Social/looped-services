ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS profile_completion_dismissed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS profile_completion_completed_at TIMESTAMPTZ;
