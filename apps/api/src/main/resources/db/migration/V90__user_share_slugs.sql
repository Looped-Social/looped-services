CREATE TABLE IF NOT EXISTS user_share_slugs (
  id          BIGSERIAL PRIMARY KEY,
  user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  slug        TEXT NOT NULL,
  type        TEXT NOT NULL,
  active      BOOLEAN NOT NULL DEFAULT true,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT user_share_slugs_type_check
    CHECK (type IN ('username_reserved', 'custom')),
  CONSTRAINT user_share_slugs_slug_format_check
    CHECK (slug = lower(slug) AND slug ~ '^[a-z0-9_]{3,30}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_share_slugs_active_slug_lower
  ON user_share_slugs (lower(slug))
  WHERE active = true;

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_share_slugs_active_username_per_user
  ON user_share_slugs (user_id)
  WHERE active = true AND type = 'username_reserved';

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_share_slugs_active_custom_per_user
  ON user_share_slugs (user_id)
  WHERE active = true AND type = 'custom';

INSERT INTO user_share_slugs(user_id, slug, type, active)
SELECT u.id, lower(u.handle), 'username_reserved', true
FROM users u
WHERE u.handle IS NOT NULL AND btrim(u.handle) <> ''
ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION sync_username_share_slug()
RETURNS trigger AS $$
BEGIN
  IF NEW.handle IS NULL OR btrim(NEW.handle) = '' THEN
    RETURN NEW;
  END IF;

  UPDATE user_share_slugs
  SET slug = lower(NEW.handle), updated_at = now(), active = true
  WHERE user_id = NEW.id
    AND type = 'username_reserved'
    AND active = true;

  IF NOT FOUND THEN
    INSERT INTO user_share_slugs(user_id, slug, type, active)
    VALUES (NEW.id, lower(NEW.handle), 'username_reserved', true);
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_users_sync_username_share_slug ON users;

CREATE TRIGGER trg_users_sync_username_share_slug
AFTER INSERT OR UPDATE OF handle ON users
FOR EACH ROW
EXECUTE FUNCTION sync_username_share_slug();
