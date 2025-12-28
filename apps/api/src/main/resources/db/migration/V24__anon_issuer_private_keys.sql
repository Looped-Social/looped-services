-- Store issuer private keys (encrypted) and enforce one issuer per scope

ALTER TABLE IF EXISTS anon_issuers
    ADD COLUMN IF NOT EXISTS private_key_enc BYTEA;

ALTER TABLE IF EXISTS anon_issuers
    ADD COLUMN IF NOT EXISTS company_id BIGINT REFERENCES companies(id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_anon_issuers_scope
    ON anon_issuers(scope_kind, scope_id);
