-- Link companies to sectors (many-to-many)

CREATE TABLE IF NOT EXISTS community_sector_links (
  sector_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
  company_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (sector_id, company_id)
);

CREATE INDEX IF NOT EXISTS idx_sector_links_sector ON community_sector_links(sector_id);
CREATE INDEX IF NOT EXISTS idx_sector_links_company ON community_sector_links(company_id);
