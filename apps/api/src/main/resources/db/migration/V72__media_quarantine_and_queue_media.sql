-- Media moderation/quarantine

ALTER TABLE IF EXISTS media_assets
    ADD COLUMN IF NOT EXISTS visibility TEXT NOT NULL DEFAULT 'public',
    ADD COLUMN IF NOT EXISTS quarantined_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS quarantine_reason TEXT,
    ADD COLUMN IF NOT EXISTS removed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS removed_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS removed_reason TEXT;

ALTER TABLE IF EXISTS media_assets
    DROP CONSTRAINT IF EXISTS chk_media_assets_visibility;

ALTER TABLE IF EXISTS media_assets
    ADD CONSTRAINT chk_media_assets_visibility
    CHECK (visibility IN ('public','quarantined'));

CREATE INDEX IF NOT EXISTS idx_media_assets_visibility ON media_assets(visibility);
CREATE INDEX IF NOT EXISTS idx_media_assets_quarantined_at ON media_assets(quarantined_at DESC) WHERE quarantined_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_media_assets_removed_at ON media_assets(removed_at DESC) WHERE removed_at IS NOT NULL;

-- Extend moderation queue to include media
ALTER TABLE IF EXISTS moderation_queue_items
    DROP CONSTRAINT IF EXISTS moderation_queue_items_target_type_check;

ALTER TABLE IF EXISTS moderation_queue_items
    ADD CONSTRAINT moderation_queue_items_target_type_check
    CHECK (target_type IN ('post','comment','media'));
