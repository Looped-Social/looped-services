ALTER TABLE communities
    ADD COLUMN IF NOT EXISTS profile_image_url TEXT;
