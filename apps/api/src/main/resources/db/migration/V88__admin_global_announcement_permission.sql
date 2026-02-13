-- Add global announcement permission to existing owners.

UPDATE admin_users
SET permissions = array_append(COALESCE(permissions, ARRAY[]::text[]), 'send_global_announcements')
WHERE role = 'owner'
  AND NOT ('send_global_announcements' = ANY(COALESCE(permissions, ARRAY[]::text[])));
