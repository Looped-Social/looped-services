-- Expired community verifications should remain "verified=true" so clients can display "Expired".
-- A verification is considered active when (expires_at IS NULL OR expires_at > now()).
-- For email-based verifications, release the email uniqueness lock by clearing email on expiry.

UPDATE community_verifications
SET verified = true
WHERE verified = false
  AND verified_at IS NOT NULL
  AND expires_at IS NOT NULL
  AND expires_at <= now();

UPDATE community_verifications
SET email = NULL
WHERE email IS NOT NULL
  AND expires_at IS NOT NULL
  AND expires_at <= now();

