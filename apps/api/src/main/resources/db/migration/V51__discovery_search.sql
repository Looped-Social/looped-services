-- Full-text indexes for discovery search (communities + hashtags + users).
CREATE INDEX IF NOT EXISTS idx_communities_fts_en
    ON communities USING gin (to_tsvector('english', COALESCE(name,'') || ' ' || COALESCE(description,'')));

CREATE INDEX IF NOT EXISTS idx_communities_fts_simple
    ON communities USING gin (to_tsvector('simple', COALESCE(name,'') || ' ' || COALESCE(short_name,'') || ' ' || COALESCE(description,'')));

CREATE INDEX IF NOT EXISTS idx_hashtags_name_fts_simple
    ON hashtags USING gin (to_tsvector('simple', COALESCE(name,'')));

CREATE INDEX IF NOT EXISTS idx_users_name_fts_simple
    ON users USING gin (to_tsvector('simple',
        COALESCE(handle,'') || ' ' || COALESCE(display_name,'') || ' ' || COALESCE(first_name,'') || ' ' || COALESCE(last_name,'')
    ))
    WHERE deleted_at IS NULL;

