-- Enforce that a community email can only be actively verified by one user at a time.
-- Expired or unverified rows should not retain the lock.

ALTER TABLE IF EXISTS community_verifications
    ADD COLUMN IF NOT EXISTS email TEXT;

-- Backfill email for active email-based verifications from the latest approved request.
-- (verification_requests.email is already normalized to lowercase when stored)
UPDATE community_verifications cv
SET email = (
    SELECT vr.email
    FROM verification_requests vr
    WHERE vr.user_id = cv.user_id
      AND vr.community_id = cv.community_id
      AND vr.method = 'email'
      AND vr.status = 'approved'
      AND vr.email IS NOT NULL
    ORDER BY vr.submitted_at DESC, vr.id DESC
    LIMIT 1
)
WHERE cv.verified = true
  AND cv.method = 'email'
  AND cv.email IS NULL;

-- Release already-expired verifications so emails become reusable.
UPDATE community_verifications
SET verified = false, email = NULL
WHERE verified = true
  AND expires_at IS NOT NULL
  AND expires_at <= now();

-- If historical data contains duplicates (same community/email verified by multiple users),
-- keep the "best" row and release the rest to avoid blocking uniqueness enforcement.
WITH ranked AS (
    SELECT user_id,
           community_id,
           email,
           ROW_NUMBER() OVER (
               PARTITION BY community_id, email
               ORDER BY (expires_at IS NULL) DESC,
                        expires_at DESC NULLS LAST,
                        verified_at DESC NULLS LAST,
                        user_id DESC
           ) AS rn
    FROM community_verifications
    WHERE verified = true
      AND email IS NOT NULL
)
UPDATE community_verifications cv
SET verified = false, verified_at = NULL, expires_at = NULL, email = NULL
FROM ranked r
WHERE cv.user_id = r.user_id
  AND cv.community_id = r.community_id
  AND r.rn > 1;

-- Only one active (verified=true) row may hold a given (community_id, email).
CREATE UNIQUE INDEX IF NOT EXISTS uq_community_verifications_active_email
    ON community_verifications(community_id, email)
    WHERE verified = true AND email IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_community_verifications_email
    ON community_verifications(email);
