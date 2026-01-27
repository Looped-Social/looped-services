-- Merge legacy departments + sectors into new specialization type "field".
-- This migration:
--  - Seeds specialization join-limit settings (majors/fields)
--  - Makes anonymous profile display community/specialization nullable on community deletion
--  - Wipes legacy department specializations and sector communities
--  - Drops the now-unused sector link table

-- Seed specialization join-limit settings (admin-editable via /v1/admin/settings/specializations).
INSERT INTO app_settings(key, value_int)
VALUES
  ('specializations.max_joins.major', 2),
  ('specializations.max_joins.field', 2)
ON CONFLICT (key) DO NOTHING;

-- Ensure anonymous profile display refs don't block community deletes.
ALTER TABLE IF EXISTS anonymous_profiles
  DROP CONSTRAINT IF EXISTS anonymous_profiles_display_community_id_fkey;
ALTER TABLE IF EXISTS anonymous_profiles
  ADD CONSTRAINT anonymous_profiles_display_community_id_fkey
  FOREIGN KEY (display_community_id) REFERENCES communities(id) ON DELETE SET NULL;

ALTER TABLE IF EXISTS anonymous_profiles
  DROP CONSTRAINT IF EXISTS anonymous_profiles_display_specialization_id_fkey;
ALTER TABLE IF EXISTS anonymous_profiles
  ADD CONSTRAINT anonymous_profiles_display_specialization_id_fkey
  FOREIGN KEY (display_specialization_id) REFERENCES communities(id) ON DELETE SET NULL;

-- Clear any legacy specialization cooldown tracking for removed types.
DELETE FROM user_specialization_limits
WHERE specialization_type = 'department';

-- Wipe legacy communities.
DELETE FROM communities
WHERE kind = 'sector'
   OR (kind = 'specialization' AND specialization_type = 'department');

-- Sector linking is no longer used.
DROP TABLE IF EXISTS community_sector_links;
