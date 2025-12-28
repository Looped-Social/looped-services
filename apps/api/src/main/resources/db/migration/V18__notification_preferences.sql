-- Notification preferences per principal

CREATE TABLE IF NOT EXISTS principal_settings (
    principal_id BIGINT PRIMARY KEY REFERENCES principals(id) ON DELETE CASCADE,
    notifications JSONB,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
