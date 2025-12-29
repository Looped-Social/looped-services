-- Community email domain allowlist + default onboarding company

CREATE TABLE IF NOT EXISTS community_domains (
  id BIGSERIAL PRIMARY KEY,
  community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
  domain TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (domain = lower(domain))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_community_domains_unique
  ON community_domains (community_id, lower(domain));

CREATE INDEX IF NOT EXISTS idx_community_domains_community
  ON community_domains (community_id);

INSERT INTO companies(name, domain)
SELECT 'Looped Global', 'looped.global'
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE domain = 'looped.global');
