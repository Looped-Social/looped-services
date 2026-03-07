-- Multi-asset post attachments must not block hard-deleting a user-owned media asset.

ALTER TABLE IF EXISTS post_media_assets
    DROP CONSTRAINT IF EXISTS post_media_assets_media_asset_id_fkey;

ALTER TABLE IF EXISTS post_media_assets
    ADD CONSTRAINT post_media_assets_media_asset_id_fkey
    FOREIGN KEY (media_asset_id) REFERENCES media_assets(id) ON DELETE CASCADE;
