-- User-selected display specialization (major/department)

ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS display_specialization_id BIGINT REFERENCES communities(id) ON DELETE SET NULL;
