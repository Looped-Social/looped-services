-- Photo + ID verification attachments (private bucket keys) and delayed deletion support

ALTER TABLE IF EXISTS verification_requests
    ADD COLUMN IF NOT EXISTS selfie_key TEXT,
    ADD COLUMN IF NOT EXISTS id_front_key TEXT,
    ADD COLUMN IF NOT EXISTS id_back_key TEXT,
    ADD COLUMN IF NOT EXISTS delete_after_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS media_deleted_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_verification_requests_delete_after_at
    ON verification_requests(delete_after_at)
    WHERE delete_after_at IS NOT NULL AND media_deleted_at IS NULL AND status = 'rejected' AND method = 'photo_id';

