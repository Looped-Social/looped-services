-- Specialization joins (majors/departments) are distinct from follows.
-- Users can follow unlimited specializations, but can only *join* a limited number per type.

CREATE TABLE IF NOT EXISTS specialization_joins (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    specialization_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, specialization_id)
);
CREATE INDEX IF NOT EXISTS idx_specialization_joins_user_created
    ON specialization_joins(user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_specialization_joins_specialization
    ON specialization_joins(specialization_id);

-- Version specialization cooldown tracking so legacy "follow-based" limits do not block join-based limits.
ALTER TABLE IF EXISTS user_specialization_limits
    ADD COLUMN IF NOT EXISTS scope TEXT NOT NULL DEFAULT 'follow';
ALTER TABLE IF EXISTS user_specialization_limits
    DROP CONSTRAINT IF EXISTS user_specialization_limits_pkey;
ALTER TABLE IF EXISTS user_specialization_limits
    ADD PRIMARY KEY (user_id, specialization_type, scope);
CREATE INDEX IF NOT EXISTS idx_user_specialization_limits_scope
    ON user_specialization_limits(user_id, specialization_type, scope);
