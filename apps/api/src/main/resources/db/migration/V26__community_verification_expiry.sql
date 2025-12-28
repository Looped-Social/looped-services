-- Community verification TTLs and expiry tracking

ALTER TABLE IF EXISTS communities
    ADD COLUMN IF NOT EXISTS verification_ttl_days INT;

ALTER TABLE IF EXISTS community_verifications
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_community_verifications_expires_at
    ON community_verifications(expires_at);

ALTER TABLE IF EXISTS verification_requests
    ADD COLUMN IF NOT EXISTS community_id BIGINT REFERENCES communities(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_verification_requests_community_id
    ON verification_requests(community_id);
