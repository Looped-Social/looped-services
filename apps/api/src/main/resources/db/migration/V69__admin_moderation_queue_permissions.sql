-- Add moderation queue permissions to existing admin roles

UPDATE admin_users
SET permissions = array(
    SELECT DISTINCT unnest(permissions || ARRAY['view_moderation_queue','resolve_moderation_queue']::text[])
)
WHERE role IN ('owner','admin','moderator')
  AND status = 'active';

