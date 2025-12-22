-- User profile visibility settings

ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS show_follower_count BOOLEAN NOT NULL DEFAULT true;
