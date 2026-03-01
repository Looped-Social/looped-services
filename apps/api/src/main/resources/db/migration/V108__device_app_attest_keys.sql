CREATE TABLE device_app_attest_keys (
  id                BIGSERIAL PRIMARY KEY,
  user_id           BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  key_id            TEXT NOT NULL UNIQUE,
  platform          TEXT NOT NULL DEFAULT 'ios',
  status            TEXT NOT NULL DEFAULT 'observed',
  last_challenge_at TIMESTAMPTZ,
  last_verified_at  TIMESTAMPTZ,
  trusted_until     TIMESTAMPTZ,
  last_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error        TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_device_app_attest_keys_user_status
  ON device_app_attest_keys(user_id, status, trusted_until);
