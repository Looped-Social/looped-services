-- Enforce unique user emails (case-insensitive)

DROP INDEX IF EXISTS idx_users_email_lower;
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_lower ON users (lower(email)) WHERE email IS NOT NULL;
