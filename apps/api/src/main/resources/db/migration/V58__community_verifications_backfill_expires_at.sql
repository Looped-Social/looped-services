-- Backfill expiry for existing community verifications.
--
-- Semantics:
-- - If communities.verification_ttl_days is NULL, default to 365 days.
-- - If communities.verification_ttl_days <= 0, treat as "never expires" (leave expires_at NULL).
-- - Only backfill rows that are verified and currently missing expires_at.

UPDATE community_verifications cv
SET expires_at = COALESCE(cv.verified_at, now()) + (COALESCE(c.verification_ttl_days, 365) || ' days')::interval
FROM communities c
WHERE c.id = cv.community_id
  AND cv.verified = true
  AND cv.expires_at IS NULL
  AND COALESCE(c.verification_ttl_days, 365) > 0;

