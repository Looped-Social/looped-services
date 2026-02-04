-- Default messaging permission for new users should be "all"
ALTER TABLE IF EXISTS users
    ALTER COLUMN message_permission SET DEFAULT 'all';

