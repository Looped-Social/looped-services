-- Make the notifications event_key idempotency index compatible with prepared statements.
-- The jsonb "?" existence operator conflicts with JDBC parameter placeholders.
-- Using a predicate based on payload->>'event_key' avoids that issue and remains correct for our usage.

DROP INDEX IF EXISTS idx_notifications_user_type_event_key_unique;

CREATE UNIQUE INDEX IF NOT EXISTS idx_notifications_user_type_event_key_unique
    ON notifications (user_id, type, (payload->>'event_key'))
    WHERE (payload->>'event_key') IS NOT NULL;

