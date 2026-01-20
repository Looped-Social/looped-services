-- Moderation blocklist terms managed via admin dashboard

CREATE TABLE IF NOT EXISTS moderation_blocklist_terms (
    id BIGSERIAL PRIMARY KEY,
    term TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    updated_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    CHECK (term = lower(term))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_moderation_blocklist_terms_term_lower
    ON moderation_blocklist_terms (lower(term));
CREATE INDEX IF NOT EXISTS idx_moderation_blocklist_terms_enabled
    ON moderation_blocklist_terms (enabled);
CREATE INDEX IF NOT EXISTS idx_moderation_blocklist_terms_updated_at
    ON moderation_blocklist_terms (updated_at DESC);

