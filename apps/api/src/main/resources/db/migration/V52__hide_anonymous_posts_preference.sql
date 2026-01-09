-- User preference: hide anonymous posts (off by default)

ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS hide_anonymous_posts BOOLEAN NOT NULL DEFAULT false;

