-- Ensure announcement permission exists for owner/admin roles

UPDATE admin_users
SET permissions = array_append(COALESCE(permissions, ARRAY[]::text[]), 'send_announcements')
WHERE role IN ('owner', 'admin')
  AND NOT ('send_announcements' = ANY(COALESCE(permissions, ARRAY[]::text[])));
