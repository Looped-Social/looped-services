-- Comment soft deletes

ALTER TABLE IF EXISTS comments
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
