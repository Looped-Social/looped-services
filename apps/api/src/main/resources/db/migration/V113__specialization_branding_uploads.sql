CREATE TABLE IF NOT EXISTS specialization_branding_assets (
    id BIGSERIAL PRIMARY KEY,
    community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    media_asset_id BIGINT NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
    slot TEXT NOT NULL CHECK (slot IN ('icon', 'banner')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (community_id, media_asset_id, slot)
);

CREATE INDEX IF NOT EXISTS idx_specialization_branding_assets_community_created
    ON specialization_branding_assets (community_id, created_at DESC, id DESC);
