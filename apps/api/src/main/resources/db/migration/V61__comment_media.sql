-- Comment media support

ALTER TABLE IF EXISTS comments
    ADD COLUMN IF NOT EXISTS media_asset_id BIGINT REFERENCES media_assets(id) ON DELETE SET NULL;

