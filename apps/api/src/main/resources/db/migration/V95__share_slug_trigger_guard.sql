-- Guard username-reserved slug sync so invalid handles cannot break user inserts.

CREATE OR REPLACE FUNCTION sync_username_share_slug()
RETURNS trigger AS $$
DECLARE
  normalized_handle TEXT;
BEGIN
  normalized_handle := lower(btrim(COALESCE(NEW.handle, '')));

  -- If handle does not satisfy slug format, do not maintain an active reserved slug.
  IF normalized_handle = '' OR normalized_handle !~ '^[a-z0-9_]{3,30}$' THEN
    UPDATE user_share_slugs
    SET active = false, updated_at = now()
    WHERE user_id = NEW.id
      AND type = 'username_reserved'
      AND active = true;
    RETURN NEW;
  END IF;

  UPDATE user_share_slugs
  SET slug = normalized_handle, updated_at = now(), active = true
  WHERE user_id = NEW.id
    AND type = 'username_reserved'
    AND active = true;

  IF NOT FOUND THEN
    INSERT INTO user_share_slugs(user_id, slug, type, active)
    VALUES (NEW.id, normalized_handle, 'username_reserved', true)
    ON CONFLICT DO NOTHING;
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Ensure current rows are consistent with the guarded trigger behavior.
UPDATE user_share_slugs s
SET active = false, updated_at = now()
FROM users u
WHERE s.user_id = u.id
  AND s.type = 'username_reserved'
  AND s.active = true
  AND (
    u.handle IS NULL
    OR btrim(u.handle) = ''
    OR lower(btrim(u.handle)) !~ '^[a-z0-9_]{3,30}$'
  );

INSERT INTO user_share_slugs(user_id, slug, type, active)
SELECT u.id, lower(btrim(u.handle)), 'username_reserved', true
FROM users u
WHERE u.handle IS NOT NULL
  AND btrim(u.handle) <> ''
  AND lower(btrim(u.handle)) ~ '^[a-z0-9_]{3,30}$'
  AND NOT EXISTS (
    SELECT 1
    FROM user_share_slugs s
    WHERE s.user_id = u.id
      AND s.type = 'username_reserved'
      AND s.active = true
  )
ON CONFLICT DO NOTHING;
