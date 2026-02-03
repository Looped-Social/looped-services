-- Idempotent notification inserts for scheduled/system events.
-- We intentionally scope uniqueness only to rows that include payload.event_key
-- so user-generated notifications (likes, follows, etc.) are unaffected.

CREATE UNIQUE INDEX IF NOT EXISTS idx_notifications_user_type_event_key_unique
    ON notifications (user_id, type, (payload->>'event_key'))
    WHERE (payload ? 'event_key');

