-- Indexes to support /v1/admin/posts/search (FTS + typo-tolerant matching).

-- The existing index idx_posts_content_fts_en is partial (removed_at IS NULL).
-- Admin search supports status=all/removed, so we also maintain an unfiltered index.
CREATE INDEX IF NOT EXISTS idx_posts_content_fts_en_all
    ON posts USING gin (to_tsvector('english', COALESCE(content, '')));

-- Typo tolerance and substring matches via pg_trgm (extension created in V82__user_search_trgm.sql).
CREATE INDEX IF NOT EXISTS idx_posts_content_trgm
    ON posts USING gin (LOWER(COALESCE(content, '')) gin_trgm_ops);

