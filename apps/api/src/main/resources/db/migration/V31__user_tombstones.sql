-- Preserve unique handles/emails after account purge

CREATE TABLE IF NOT EXISTS user_tombstones (
  id BIGSERIAL PRIMARY KEY,
  firebase_uid TEXT NOT NULL,
  handle TEXT NOT NULL,
  email TEXT,
  purged_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'user_tombstones_email_lower'
    ) THEN
        ALTER TABLE user_tombstones
            ADD CONSTRAINT user_tombstones_email_lower CHECK (email IS NULL OR email = lower(email));
    END IF;
END$$;

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_tombstones_firebase_uid ON user_tombstones (firebase_uid);
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_tombstones_handle_lower ON user_tombstones (lower(handle));
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_tombstones_email_lower ON user_tombstones (lower(email)) WHERE email IS NOT NULL;
