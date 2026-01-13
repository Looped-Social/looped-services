-- Specialization (major/department) join cooldowns: default + per-community override.

ALTER TABLE IF EXISTS communities
    ADD COLUMN IF NOT EXISTS specialization_join_cooldown_months INT;

ALTER TABLE IF EXISTS user_specialization_limits
    ADD COLUMN IF NOT EXISTS cooldown_months INT;

-- App-level settings (admin editable).
CREATE TABLE IF NOT EXISTS app_settings (
    key TEXT PRIMARY KEY,
    value_int BIGINT,
    value_text TEXT,
    value_json JSONB,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_admin_id BIGINT REFERENCES admin_users(id) ON DELETE SET NULL
);

-- Seed default specialization join cooldown months (6).
INSERT INTO app_settings(key, value_int)
VALUES ('specializations.default_join_cooldown_months', 6)
ON CONFLICT (key) DO NOTHING;

-- Backfill existing join-scope cooldowns so future default changes do not retroactively change ongoing cooldown windows.
UPDATE user_specialization_limits
SET cooldown_months = 6
WHERE scope = 'join' AND cooldown_months IS NULL;

