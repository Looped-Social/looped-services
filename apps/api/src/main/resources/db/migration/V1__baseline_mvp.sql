-- Baseline MVP schema for Looped

-- companies
CREATE TABLE IF NOT EXISTS companies (
  id            BIGSERIAL PRIMARY KEY,
  name          TEXT NOT NULL,
  domain        TEXT NOT NULL UNIQUE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- users (linked to Firebase identity)
CREATE TABLE IF NOT EXISTS users (
  id            BIGSERIAL PRIMARY KEY,
  firebase_uid  TEXT NOT NULL UNIQUE,
  handle        TEXT NOT NULL UNIQUE,
  company_id    BIGINT NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- verifications (one row per user)
CREATE TABLE IF NOT EXISTS verifications (
  user_id       BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  method        TEXT NOT NULL,             -- e.g., 'linkedin','email','hr','manual'
  verified      BOOLEAN NOT NULL DEFAULT false,
  verified_at   TIMESTAMPTZ
);

-- media_assets
CREATE TABLE IF NOT EXISTS media_assets (
  id               BIGSERIAL PRIMARY KEY,
  owner_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  s3_key           TEXT NOT NULL UNIQUE,
  mime_type        TEXT NOT NULL,
  width            INT,
  height           INT,
  duration_seconds INT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- posts
CREATE TABLE IF NOT EXISTS posts (
  id               BIGSERIAL PRIMARY KEY,
  author_id        BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  company_id       BIGINT NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
  content          TEXT NOT NULL,
  media_asset_id   BIGINT REFERENCES media_assets(id) ON DELETE SET NULL,
  reactions_count  INT NOT NULL DEFAULT 0,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_posts_company_created_at_desc ON posts(company_id, created_at DESC);

-- reactions
CREATE TABLE IF NOT EXISTS reactions (
  id         BIGSERIAL PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  post_id    BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  type       TEXT NOT NULL,                -- e.g., 'like','clap'
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, post_id)
);

-- reports
CREATE TABLE IF NOT EXISTS reports (
  id           BIGSERIAL PRIMARY KEY,
  target_type  TEXT NOT NULL,              -- e.g., 'post','user','comment'
  target_id    BIGINT NOT NULL,
  reporter_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  reason       TEXT NOT NULL,
  status       TEXT NOT NULL DEFAULT 'open',  -- 'open','resolved','dismissed'
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- devices (idempotent by apns_token)
CREATE TABLE IF NOT EXISTS devices (
  id          BIGSERIAL PRIMARY KEY,
  user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  apns_token  TEXT NOT NULL UNIQUE,
  platform    TEXT NOT NULL,                -- 'ios'
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

