-- Video thumbnails: allow associating a thumbnail image asset to a video asset.

ALTER TABLE IF EXISTS media_assets
    ADD COLUMN IF NOT EXISTS thumbnail_media_asset_id BIGINT REFERENCES media_assets(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_media_assets_thumbnail_media_asset_id ON media_assets(thumbnail_media_asset_id);

