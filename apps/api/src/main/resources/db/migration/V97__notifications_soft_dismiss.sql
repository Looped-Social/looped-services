-- Soft-dismiss support for notifications.
ALTER TABLE IF EXISTS notifications
    ADD COLUMN IF NOT EXISTS dismissed_at TIMESTAMPTZ;

-- Default notification listing excludes dismissed rows.
CREATE INDEX IF NOT EXISTS idx_notifications_user_undismissed_created
    ON notifications(user_id, created_at DESC, id DESC)
    WHERE dismissed_at IS NULL;
