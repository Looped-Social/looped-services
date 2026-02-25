-- Track user deletion lifecycle for client polling and onboarding locks.

CREATE TABLE IF NOT EXISTS user_deletion_operations (
  id BIGSERIAL PRIMARY KEY,
  operation_id UUID NOT NULL,
  firebase_uid TEXT NOT NULL,
  user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
  requested_email TEXT,
  mode TEXT NOT NULL DEFAULT 'hard',
  state TEXT NOT NULL,
  error_code TEXT,
  error_message TEXT,
  requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_deletion_ops_operation_id
    ON user_deletion_operations(operation_id);
CREATE INDEX IF NOT EXISTS idx_user_deletion_ops_uid_requested_desc
    ON user_deletion_operations(firebase_uid, requested_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_user_deletion_ops_active_uid
    ON user_deletion_operations(firebase_uid) WHERE state IN ('in_progress', 'pending');
CREATE INDEX IF NOT EXISTS idx_user_deletion_ops_active_email_lower
    ON user_deletion_operations(lower(requested_email))
    WHERE requested_email IS NOT NULL AND state IN ('in_progress', 'pending');

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'user_deletion_ops_mode_check'
    ) THEN
        ALTER TABLE user_deletion_operations
            ADD CONSTRAINT user_deletion_ops_mode_check
            CHECK (mode IN ('hard', 'soft'));
    END IF;
END$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'user_deletion_ops_state_check'
    ) THEN
        ALTER TABLE user_deletion_operations
            ADD CONSTRAINT user_deletion_ops_state_check
            CHECK (state IN ('in_progress', 'pending', 'completed', 'failed'));
    END IF;
END$$;
