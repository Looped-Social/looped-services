-- Channel ownership and member management permissions
ALTER TABLE IF EXISTS channels
    ADD COLUMN IF NOT EXISTS owner_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL;

UPDATE channels c
SET owner_user_id = (
    SELECT cm.user_id
    FROM channel_members cm
    WHERE cm.channel_id = c.id
    ORDER BY cm.created_at ASC, cm.user_id ASC
    LIMIT 1
)
WHERE c.owner_user_id IS NULL;

ALTER TABLE IF EXISTS channel_members
    ADD COLUMN IF NOT EXISTS can_manage_members BOOLEAN NOT NULL DEFAULT false;

UPDATE channel_members cm
SET can_manage_members = true
FROM channels c
WHERE c.id = cm.channel_id AND c.owner_user_id = cm.user_id;

CREATE INDEX IF NOT EXISTS idx_channel_members_channel_created
    ON channel_members(channel_id, created_at DESC, user_id DESC);
