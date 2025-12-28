-- Message requests for DMs when recipient does not follow sender
CREATE TABLE IF NOT EXISTS conversation_message_requests (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    requester_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipient_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message_id BIGINT NOT NULL REFERENCES conversation_messages(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (status IN ('pending', 'approved', 'rejected'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_conv_msg_requests_conversation_recipient
    ON conversation_message_requests(conversation_id, recipient_id);

CREATE INDEX IF NOT EXISTS idx_conv_msg_requests_recipient_status
    ON conversation_message_requests(recipient_id, status, updated_at DESC, id DESC);
