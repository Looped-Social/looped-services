-- Typo-tolerant user search support (pg_trgm)
--
-- Used by /v1/users/search ranking to better handle partial input and small typos.
-- Safe to run repeatedly.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_users_handle_trgm
    ON users USING gin (LOWER(handle) gin_trgm_ops)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_display_name_trgm
    ON users USING gin (LOWER(display_name) gin_trgm_ops)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_first_name_trgm
    ON users USING gin (LOWER(first_name) gin_trgm_ops)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_last_name_trgm
    ON users USING gin (LOWER(last_name) gin_trgm_ops)
    WHERE deleted_at IS NULL;

