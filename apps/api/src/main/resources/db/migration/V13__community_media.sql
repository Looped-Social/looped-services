-- Community media fields

ALTER TABLE IF EXISTS communities
    ADD COLUMN IF NOT EXISTS image_url TEXT;
