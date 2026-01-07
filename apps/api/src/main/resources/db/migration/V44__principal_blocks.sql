CREATE TABLE IF NOT EXISTS principal_blocks (
    blocker_principal_id BIGINT NOT NULL REFERENCES principals(id) ON DELETE CASCADE,
    blocked_principal_id BIGINT NOT NULL REFERENCES principals(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_principal_id, blocked_principal_id)
);

CREATE INDEX IF NOT EXISTS idx_principal_blocks_blocked ON principal_blocks(blocked_principal_id);
