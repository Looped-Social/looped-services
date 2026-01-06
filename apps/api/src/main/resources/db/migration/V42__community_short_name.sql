-- Add optional shorthand/nickname for communities.

ALTER TABLE communities
    ADD COLUMN IF NOT EXISTS short_name TEXT;
