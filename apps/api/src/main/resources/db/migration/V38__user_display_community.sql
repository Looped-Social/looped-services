-- User-selected display community (works at / attends)

ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS display_community_id BIGINT REFERENCES communities(id) ON DELETE SET NULL;
