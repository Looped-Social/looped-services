-- Anonymous profile display community
ALTER TABLE IF EXISTS anonymous_profiles
    ADD COLUMN IF NOT EXISTS display_community_id BIGINT REFERENCES communities(id);

ALTER TABLE IF EXISTS anonymous_profiles
    ADD COLUMN IF NOT EXISTS display_community_cert_kid TEXT;
