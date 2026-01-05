-- Allow email reuse after hard delete by removing tombstone email retention.

UPDATE user_tombstones SET email = NULL WHERE email IS NOT NULL;
DROP INDEX IF EXISTS idx_user_tombstones_email_lower;
