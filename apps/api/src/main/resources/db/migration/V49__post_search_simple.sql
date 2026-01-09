-- Prefix matching uses simple dictionary (avoids stopword stripping).
CREATE INDEX IF NOT EXISTS idx_posts_content_fts_simple
    ON posts USING gin (to_tsvector('simple', COALESCE(content, '')))
    WHERE removed_at IS NULL;

