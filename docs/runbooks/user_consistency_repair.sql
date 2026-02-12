-- User Consistency Repair (one-time runbook script)
-- Intended for targeted repair in staging/prod for a single impacted account.
-- Run inside a transaction and inspect every RETURNING row before COMMIT.
--
-- Usage with psql variables:
--   \set firebase_uid 'uid-from-id-token'
--   \set email 'user@example.com'
--   \set dry_run 'true'   -- set to false before applying writes

BEGIN;

-- 1) Snapshot account + principal state by uid/email
SELECT now() AS observed_at_utc;

SELECT
    u.id,
    u.firebase_uid,
    u.email,
    u.deleted_at,
    u.deleted_source,
    u.disabled_at,
    p.id AS principal_id,
    p.kind,
    p.user_id
FROM users u
LEFT JOIN principals p ON p.user_id = u.id
WHERE u.firebase_uid = :'firebase_uid'
   OR lower(coalesce(u.email, '')) = lower(:'email')
ORDER BY u.id;

-- 2) Identify cross-provider mismatch candidates
SELECT
    u.id,
    u.firebase_uid AS existing_firebase_uid,
    :'firebase_uid'::text AS incoming_firebase_uid,
    u.email
FROM users u
WHERE lower(coalesce(u.email, '')) = lower(:'email')
  AND u.deleted_at IS NULL
  AND u.firebase_uid <> :'firebase_uid';

-- 3) Repair named-post corruption: non-anon post missing author_id
--    This can block hard-delete and create "text remains, media gone" states.
-- dry-run view:
SELECT
    p.id AS post_id,
    p.author_id,
    p.author_principal_id,
    p.is_anon,
    pr.user_id AS principal_user_id
FROM posts p
JOIN principals pr ON pr.id = p.author_principal_id
WHERE p.author_id IS NULL
  AND coalesce(p.is_anon, false) = false
  AND pr.user_id IS NOT NULL
  AND (pr.user_id IN (
        SELECT id FROM users
        WHERE firebase_uid = :'firebase_uid'
           OR lower(coalesce(email, '')) = lower(:'email')
  ));

-- apply fix:
-- UPDATE posts p
-- SET author_id = pr.user_id
-- FROM principals pr
-- WHERE p.author_id IS NULL
--   AND coalesce(p.is_anon, false) = false
--   AND p.author_principal_id = pr.id
--   AND pr.user_id IN (
--         SELECT id FROM users
--         WHERE firebase_uid = :'firebase_uid'
--            OR lower(coalesce(email, '')) = lower(:'email')
--   )
-- RETURNING p.id, p.author_id, p.author_principal_id;

-- 4) Reconcile active account mapping by verified email (if exactly one candidate)
-- dry-run candidate:
SELECT
    u.id,
    u.firebase_uid AS before_firebase_uid,
    :'firebase_uid'::text AS after_firebase_uid,
    u.email
FROM users u
WHERE lower(coalesce(u.email, '')) = lower(:'email')
  AND u.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM users x WHERE x.firebase_uid = :'firebase_uid'
  );

-- apply fix:
-- UPDATE users u
-- SET firebase_uid = :'firebase_uid'
-- WHERE lower(coalesce(u.email, '')) = lower(:'email')
--   AND u.deleted_at IS NULL
--   AND NOT EXISTS (
--       SELECT 1 FROM users x WHERE x.firebase_uid = :'firebase_uid'
--   )
-- RETURNING u.id, u.firebase_uid, u.email;

-- 5) Optional: complete deletion for self-deleted zombie rows
--    (only run after confirming account should be terminally deleted).
-- dry-run:
SELECT id, firebase_uid, handle, deleted_at, deleted_source
FROM users
WHERE (firebase_uid = :'firebase_uid' OR lower(coalesce(email, '')) = lower(:'email'))
  AND deleted_at IS NOT NULL
  AND deleted_source IN ('self', 'repair');

-- apply hard-delete + tombstone:
-- WITH deleted AS (
--   DELETE FROM users
--   WHERE (firebase_uid = :'firebase_uid' OR lower(coalesce(email, '')) = lower(:'email'))
--     AND deleted_at IS NOT NULL
--     AND deleted_source IN ('self', 'repair')
--   RETURNING firebase_uid, handle
-- )
-- INSERT INTO user_tombstones(firebase_uid, handle)
-- SELECT d.firebase_uid, d.handle FROM deleted d
-- ON CONFLICT DO NOTHING
-- RETURNING firebase_uid, handle;

-- 6) Post-check
SELECT
    u.id,
    u.firebase_uid,
    u.email,
    u.deleted_at,
    u.deleted_source,
    p.id AS principal_id
FROM users u
LEFT JOIN principals p ON p.user_id = u.id
WHERE u.firebase_uid = :'firebase_uid'
   OR lower(coalesce(u.email, '')) = lower(:'email')
ORDER BY u.id;

-- COMMIT;
ROLLBACK;
