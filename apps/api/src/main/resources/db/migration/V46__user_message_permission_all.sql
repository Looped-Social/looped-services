-- Allow "all" messaging permission
ALTER TABLE IF EXISTS users
    DROP CONSTRAINT IF EXISTS users_message_permission_check;

ALTER TABLE IF EXISTS users
    ADD CONSTRAINT users_message_permission_check
        CHECK (message_permission IN ('company', 'following', 'no_one', 'all'));
