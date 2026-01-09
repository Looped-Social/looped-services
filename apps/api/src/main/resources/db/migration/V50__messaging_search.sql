-- Full-text indexes for messaging search (DMs + channels).
CREATE INDEX IF NOT EXISTS idx_conversation_messages_content_fts_en
    ON conversation_messages USING gin (to_tsvector('english', COALESCE(content, '')));

CREATE INDEX IF NOT EXISTS idx_conversation_messages_content_fts_simple
    ON conversation_messages USING gin (to_tsvector('simple', COALESCE(content, '')));

CREATE INDEX IF NOT EXISTS idx_channel_messages_content_fts_en
    ON channel_messages USING gin (to_tsvector('english', COALESCE(content, '')));

CREATE INDEX IF NOT EXISTS idx_channel_messages_content_fts_simple
    ON channel_messages USING gin (to_tsvector('simple', COALESCE(content, '')));

