-- Persist blocklist match details on moderation queue items for admin debugging

ALTER TABLE IF EXISTS moderation_queue_items
    ADD COLUMN IF NOT EXISTS matched_term TEXT,
    ADD COLUMN IF NOT EXISTS blocklist_term_id BIGINT REFERENCES moderation_blocklist_terms(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS match_mode TEXT,
    ADD COLUMN IF NOT EXISTS normalized_text TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'moderation_queue_items_match_mode_check'
    ) THEN
        ALTER TABLE moderation_queue_items
            ADD CONSTRAINT moderation_queue_items_match_mode_check
                CHECK (match_mode IS NULL OR match_mode IN ('word-boundary', 'regex', 'substring'));
    END IF;
END$$;

CREATE INDEX IF NOT EXISTS idx_moderation_queue_items_blocklist_term_id
    ON moderation_queue_items(blocklist_term_id)
    WHERE blocklist_term_id IS NOT NULL;
