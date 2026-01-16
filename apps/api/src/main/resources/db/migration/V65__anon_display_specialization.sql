-- Anonymous profile display specialization (major/department)
ALTER TABLE IF EXISTS anonymous_profiles
    ADD COLUMN IF NOT EXISTS display_specialization_id BIGINT REFERENCES communities(id);

ALTER TABLE IF EXISTS anonymous_profiles
    ADD COLUMN IF NOT EXISTS display_specialization_cert_kid TEXT;

