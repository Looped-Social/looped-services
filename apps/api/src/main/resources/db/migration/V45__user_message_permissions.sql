-- User messaging permissions (who can start new conversations/requests)
ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS message_permission TEXT NOT NULL DEFAULT 'company';

ALTER TABLE IF EXISTS users
    ADD CONSTRAINT users_message_permission_check
        CHECK (message_permission IN ('company', 'following', 'no_one'));
