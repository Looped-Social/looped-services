-- Allow anon profiles without company linkage and enforce global handle uniqueness

ALTER TABLE IF EXISTS anonymous_profiles
    ALTER COLUMN company_id DROP NOT NULL;

ALTER TABLE IF EXISTS anonymous_profiles
    DROP CONSTRAINT IF EXISTS anonymous_profiles_company_id_handle_key;

CREATE UNIQUE INDEX IF NOT EXISTS idx_anonymous_profiles_handle
    ON anonymous_profiles(handle);
