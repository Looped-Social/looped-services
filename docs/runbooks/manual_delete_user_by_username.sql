-- One-time manual hard-delete of a user by username/handle.
-- Defaults to dry-run (ROLLBACK).
--
-- Usage:
--   psql "$DATABASE_URL" \
--     -v username='some_handle' \
--     -v dry_run='true' \
--     -f docs/runbooks/manual_delete_user_by_username.sql
--
-- Set dry_run=false to COMMIT.
-- Note: This only deletes from Postgres. If needed, delete the Firebase Auth user separately.

\set ON_ERROR_STOP on

\if :{?username}
\else
  \echo 'Missing required variable: -v username=...'
  \quit 1
\endif

\if :{?dry_run}
\else
  \set dry_run 'true'
\endif

BEGIN;

DROP TABLE IF EXISTS _target_user;
CREATE TEMP TABLE _target_user AS
SELECT id, firebase_uid, handle, email, deleted_at, deleted_source
FROM users
WHERE lower(handle) = lower(ltrim(:'username', '@'));

SELECT now() AS observed_at_utc;
SELECT * FROM _target_user;

DO $$
DECLARE
    matched_count INT;
BEGIN
    SELECT COUNT(*) INTO matched_count FROM _target_user;
    IF matched_count <> 1 THEN
        RAISE EXCEPTION 'Expected exactly 1 user for handle %, found %', ltrim(:'username', '@'), matched_count;
    END IF;
END $$;

-- Repair known historical corruption that can block hard-delete:
-- non-anon posts/comments/comment_likes with NULL legacy user columns.
UPDATE posts p
SET author_id = t.id
FROM _target_user t
JOIN principals pr ON pr.user_id = t.id
WHERE p.author_id IS NULL
  AND COALESCE(p.is_anon, false) = false
  AND p.author_principal_id = pr.id
RETURNING p.id AS repaired_post_id;

UPDATE comments c
SET user_id = t.id
FROM _target_user t
JOIN principals pr ON pr.user_id = t.id
WHERE c.user_id IS NULL
  AND c.author_principal_id = pr.id
RETURNING c.id AS repaired_comment_id;

UPDATE comment_likes cl
SET user_id = t.id
FROM _target_user t
JOIN principals pr ON pr.user_id = t.id
WHERE cl.user_id IS NULL
  AND cl.liker_principal_id = pr.id
RETURNING cl.id AS repaired_comment_like_id;

-- Useful pre-delete visibility.
SELECT
  (SELECT COUNT(*) FROM posts p JOIN _target_user t ON p.author_id = t.id) AS posts_by_user,
  (SELECT COUNT(*) FROM comments c JOIN _target_user t ON c.user_id = t.id) AS comments_by_user,
  (SELECT COUNT(*) FROM comment_likes cl JOIN _target_user t ON cl.user_id = t.id) AS comment_likes_by_user,
  (SELECT COUNT(*) FROM devices d JOIN _target_user t ON d.user_id = t.id) AS devices_by_user,
  (SELECT COUNT(*) FROM community_verifications cv JOIN _target_user t ON cv.user_id = t.id) AS community_verifications_by_user,
  (SELECT COUNT(*) FROM verification_requests vr JOIN _target_user t ON vr.user_id = t.id) AS verification_requests_by_user;

DROP TABLE IF EXISTS _deleted_user;
CREATE TEMP TABLE _deleted_user AS
WITH deleted AS (
    DELETE FROM users u
    USING _target_user t
    WHERE u.id = t.id
    RETURNING u.id, u.firebase_uid, u.handle, u.email
)
SELECT * FROM deleted;

SELECT * FROM _deleted_user;

INSERT INTO user_tombstones(firebase_uid, handle)
SELECT firebase_uid, handle
FROM _deleted_user
ON CONFLICT DO NOTHING
RETURNING id, firebase_uid, handle, purged_at;

SELECT
  (SELECT COUNT(*) FROM users u JOIN _target_user t ON u.id = t.id) AS users_remaining_by_id,
  (SELECT COUNT(*) FROM users u JOIN _target_user t ON lower(u.handle) = lower(t.handle)) AS users_remaining_by_handle,
  (SELECT COUNT(*) FROM users u JOIN _target_user t ON t.email IS NOT NULL AND lower(u.email) = lower(t.email)) AS users_remaining_by_email;

\if :dry_run
  ROLLBACK;
  \echo 'Dry-run complete: rolled back.'
\else
  COMMIT;
  \echo 'Committed.'
\endif
