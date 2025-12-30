-- Specializations (majors/departments)

ALTER TABLE IF EXISTS communities
    ADD COLUMN IF NOT EXISTS specialization_type TEXT;

ALTER TABLE IF EXISTS communities
    DROP CONSTRAINT IF EXISTS communities_kind_name_key;

CREATE UNIQUE INDEX IF NOT EXISTS idx_communities_kind_name_unique
    ON communities(kind, lower(name))
    WHERE kind <> 'specialization';

CREATE UNIQUE INDEX IF NOT EXISTS idx_communities_specialization_unique
    ON communities(kind, specialization_type, lower(name))
    WHERE kind = 'specialization';

CREATE INDEX IF NOT EXISTS idx_communities_specialization_type_name
    ON communities(specialization_type, lower(name))
    WHERE kind = 'specialization';

CREATE TABLE IF NOT EXISTS user_specialization_limits (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    specialization_type TEXT NOT NULL,
    last_changed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, specialization_type)
);
