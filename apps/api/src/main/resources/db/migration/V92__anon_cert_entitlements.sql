-- Bind anonymous certs to issuing user/community for live entitlement checks.
-- This enables immediate loss of anon action access when verification expires
-- or specialization membership is removed.

CREATE TABLE IF NOT EXISTS anon_issue_tokens (
    id BIGSERIAL PRIMARY KEY,
    token_hash BYTEA NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_anon_issue_tokens_user_community
    ON anon_issue_tokens(user_id, community_id, expires_at);

CREATE TABLE IF NOT EXISTS anon_cert_entitlements (
    cert_fingerprint BYTEA PRIMARY KEY,
    anon_cert_kid TEXT NOT NULL,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    cert_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_anon_cert_entitlements_user_community
    ON anon_cert_entitlements(user_id, community_id);
