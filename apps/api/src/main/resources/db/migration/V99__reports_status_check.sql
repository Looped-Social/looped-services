-- Harden report status values to known moderation states.

UPDATE reports
SET status = 'open',
    updated_at = now()
WHERE status IS NULL
   OR status NOT IN ('open', 'resolved', 'dismissed');

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'reports_status_check'
    ) THEN
        ALTER TABLE reports
            ADD CONSTRAINT reports_status_check
                CHECK (status IN ('open', 'resolved', 'dismissed'));
    END IF;
END$$;
