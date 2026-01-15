-- Additional ASC indexes for efficient "newer-than-cursor" polling

CREATE INDEX IF NOT EXISTS idx_conversation_messages_conv_created_asc
    ON conversation_messages(conversation_id, created_at ASC, id ASC);

CREATE INDEX IF NOT EXISTS idx_channel_messages_channel_created_asc
    ON channel_messages(channel_id, created_at ASC, id ASC);

