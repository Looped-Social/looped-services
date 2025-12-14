-- Loops and hashtags search

CREATE TABLE IF NOT EXISTS loops (
    id           BIGSERIAL PRIMARY KEY,
    company_id   BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    description  TEXT,
    member_count INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, name)
);
CREATE INDEX IF NOT EXISTS idx_loops_company_created ON loops(company_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_loops_company_name_search ON loops(company_id, lower(name));

CREATE TABLE IF NOT EXISTS hashtags (
    id           BIGSERIAL PRIMARY KEY,
    company_id   BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    usage_count  INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, name)
);
CREATE INDEX IF NOT EXISTS idx_hashtags_company_created ON hashtags(company_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_hashtags_company_name_search ON hashtags(company_id, lower(name));
