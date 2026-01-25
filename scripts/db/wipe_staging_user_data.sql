-- One-time staging reset: wipe all user/anon/content data while keeping:
-- - companies, communities (+ domains/sectors), loops (reference data)
-- - admin_users (+ dashboard access) and admin_audit_log
-- - community_logo_assets and the media_assets rows they reference
-- - app_settings, moderation_blocklist_terms
--
-- Intended to be run manually in the Neon SQL console for STAGING ONLY.
--
-- SAFETY (required):
-- 1) Set `__EXPECTED_DATABASE__` to the exact value of `SELECT current_database();` on staging.
--    Note: Neon commonly uses `neondb` for many branches, so this check alone is NOT sufficient.
-- 2) Set `__EXPECTED_WIPE_GUARD_TOKEN__` to a random value and ensure this exact marker exists in STAGING:
--      INSERT INTO app_settings(key, value_text)
--      VALUES ('staging.wipe_guard_token', '__EXPECTED_WIPE_GUARD_TOKEN__')
--      ON CONFLICT (key) DO UPDATE SET value_text = EXCLUDED.value_text;
--    Do NOT set this marker in prod.
DO $$
DECLARE
  expected_database TEXT := '__EXPECTED_DATABASE__';
  expected_guard_token TEXT := '__EXPECTED_WIPE_GUARD_TOKEN__';
BEGIN
  IF expected_database = '__EXPECTED_DATABASE__' THEN
    RAISE EXCEPTION 'Refusing to run: set __EXPECTED_DATABASE__ in this script';
  END IF;
  IF expected_guard_token = '__EXPECTED_WIPE_GUARD_TOKEN__' THEN
    RAISE EXCEPTION 'Refusing to run: set __EXPECTED_WIPE_GUARD_TOKEN__ in this script';
  END IF;
  IF current_database() <> expected_database THEN
    RAISE EXCEPTION 'Refusing to run: expected database %, got %', expected_database, current_database();
  END IF;
  IF NOT EXISTS (
      SELECT 1
      FROM app_settings
      WHERE key = 'staging.wipe_guard_token'
        AND value_text = expected_guard_token
  ) THEN
    RAISE EXCEPTION 'Refusing to run: missing staging marker app_settings(staging.wipe_guard_token) = expected token';
  END IF;
END $$;

BEGIN;

-- Preserve community logo media even if it currently has an owner_id referencing a user.
UPDATE media_assets
SET owner_id = NULL
WHERE id IN (SELECT cla.media_asset_id FROM community_logo_assets cla);

-- Wipe user/anon/content data.
--
-- NOTE: We use DELETE (not TRUNCATE) so this can run under typical app roles
-- that have DML privileges but not TRUNCATE privileges.
--
-- Delete roots that cascade heavily.
DELETE FROM posts;          -- cascades to comments, post_* joins, poll_*, principal_* post actions, etc.
DELETE FROM conversations;  -- cascades to conversation_* tables.
DELETE FROM channels;       -- cascades to channel_* tables.

-- Delete non-cascading or "reference-like but user-generated" tables.
DELETE FROM hashtags;
DELETE FROM moderation_queue_items;
DELETE FROM user_tombstones;

-- Delete anonymous system state (not tied to users via FK cascades).
DELETE FROM anon_revocations;
DELETE FROM anon_backup_blobs;
DELETE FROM anon_handle_counters;
DELETE FROM anon_issuers;

-- Delete principals explicitly (should already be unreferenced after posts deletion).
DELETE FROM principals;

-- Delete anon profiles + users (FK cascades clean up remaining user-owned rows).
DELETE FROM anonymous_profiles;
DELETE FROM users;

-- Remove any media not required for community logos.
DELETE FROM media_assets ma
WHERE NOT EXISTS (
  SELECT 1 FROM community_logo_assets cla WHERE cla.media_asset_id = ma.id
);

-- Reset reference counters to match the now-empty user graph.
UPDATE communities SET member_count = 0;
UPDATE loops SET member_count = 0;

COMMIT;

-- Optional sanity checks (should all be 0 except kept reference/admin tables).
-- SELECT 'users' AS table, COUNT(*) FROM users
-- UNION ALL SELECT 'posts', COUNT(*) FROM posts
-- UNION ALL SELECT 'comments', COUNT(*) FROM comments
-- UNION ALL SELECT 'principals', COUNT(*) FROM principals
-- UNION ALL SELECT 'anonymous_profiles', COUNT(*) FROM anonymous_profiles;
