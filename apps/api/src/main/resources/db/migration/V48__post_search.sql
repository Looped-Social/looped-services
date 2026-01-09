-- Full-text indexes for post search (content).
CREATE INDEX IF NOT EXISTS idx_posts_content_fts_en
    ON posts USING gin (to_tsvector('english', COALESCE(content, '')))
    WHERE removed_at IS NULL;

