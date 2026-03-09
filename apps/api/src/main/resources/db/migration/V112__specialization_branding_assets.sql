ALTER TABLE communities
    ADD COLUMN IF NOT EXISTS specialization_icon_media_asset_id BIGINT REFERENCES media_assets(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS specialization_banner_media_asset_id BIGINT REFERENCES media_assets(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS specialization_icon_image_url TEXT,
    ADD COLUMN IF NOT EXISTS specialization_banner_image_url TEXT;
