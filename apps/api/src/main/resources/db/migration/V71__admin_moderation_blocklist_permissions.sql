-- Add moderation blocklist permissions to existing admin roles

UPDATE admin_users
SET permissions = array(
    SELECT DISTINCT unnest(permissions || ARRAY['manage_moderation_blocklist']::text[])
)
WHERE role IN ('owner','admin','moderator')
  AND status = 'active';

