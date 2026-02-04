-- Specialization (major/field) icon support for mobile clients.
-- Stored on communities rows where kind = 'specialization' and specialization_type IN ('major','field').

ALTER TABLE IF EXISTS communities
    ADD COLUMN IF NOT EXISTS icon_kind TEXT NOT NULL DEFAULT 'emoji',
    ADD COLUMN IF NOT EXISTS icon_value VARCHAR(128),
    ADD COLUMN IF NOT EXISTS icon_updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE IF EXISTS communities
    DROP CONSTRAINT IF EXISTS communities_icon_kind_check;

ALTER TABLE IF EXISTS communities
    ADD CONSTRAINT communities_icon_kind_check
    CHECK (icon_kind IN ('emoji', 'sf_symbol', 'image_url'));

