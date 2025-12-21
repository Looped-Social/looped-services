-- Anonymous principals, profiles, and actor-based social graph

-- Anonymous profiles (no user FK by design)
CREATE TABLE IF NOT EXISTS anonymous_profiles (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    public_key BYTEA NOT NULL UNIQUE,
    handle TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, handle)
);

CREATE TABLE IF NOT EXISTS anon_handle_counters (
    company_id BIGINT PRIMARY KEY REFERENCES companies(id) ON DELETE CASCADE,
    next_value BIGINT NOT NULL
);

-- Actor abstraction (user or anon)
CREATE TABLE IF NOT EXISTS principals (
    id BIGSERIAL PRIMARY KEY,
    kind TEXT NOT NULL,
    user_id BIGINT UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    anon_profile_id BIGINT UNIQUE REFERENCES anonymous_profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (
        (kind = 'user' AND user_id IS NOT NULL AND anon_profile_id IS NULL) OR
        (kind = 'anon' AND user_id IS NULL AND anon_profile_id IS NOT NULL)
    )
);

-- Anonymous issuer keys (public only; private in config)
CREATE TABLE IF NOT EXISTS anon_issuers (
    id BIGSERIAL PRIMARY KEY,
    kid TEXT UNIQUE NOT NULL,
    alg TEXT NOT NULL,
    public_key BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    rotated_at TIMESTAMPTZ,
    scope_kind TEXT,
    scope_id BIGINT,
    expires_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS anon_revocations (
    id BIGSERIAL PRIMARY KEY,
    persona_pubkey BYTEA,
    cert_fingerprint BYTEA,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS anon_backup_blobs (
    blob_id UUID PRIMARY KEY,
    salt BYTEA NOT NULL,
    ciphertext BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS anon_enrollment_sanctions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope_kind TEXT NOT NULL,
    scope_id BIGINT,
    status TEXT NOT NULL DEFAULT 'active',
    reason TEXT,
    imposed_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    imposed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Principal-based social graph
CREATE TABLE IF NOT EXISTS principal_follows (
    follower_principal_id BIGINT NOT NULL REFERENCES principals(id) ON DELETE CASCADE,
    followee_principal_id BIGINT NOT NULL REFERENCES principals(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_principal_id, followee_principal_id)
);
CREATE INDEX IF NOT EXISTS idx_principal_follows_followee ON principal_follows(followee_principal_id);

CREATE TABLE IF NOT EXISTS principal_saved_posts (
    saver_principal_id BIGINT NOT NULL REFERENCES principals(id) ON DELETE CASCADE,
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (saver_principal_id, post_id)
);
CREATE INDEX IF NOT EXISTS idx_principal_saved_posts_post ON principal_saved_posts(post_id);

CREATE TABLE IF NOT EXISTS post_likes (
    liker_principal_id BIGINT NOT NULL REFERENCES principals(id) ON DELETE CASCADE,
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (liker_principal_id, post_id)
);
CREATE INDEX IF NOT EXISTS idx_post_likes_post ON post_likes(post_id);

-- Posts: add anon + principal fields; allow anon author
ALTER TABLE IF EXISTS posts
    ADD COLUMN IF NOT EXISTS author_principal_id BIGINT REFERENCES principals(id),
    ADD COLUMN IF NOT EXISTS is_anon BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS anon_profile_id BIGINT REFERENCES anonymous_profiles(id),
    ADD COLUMN IF NOT EXISTS anon_company_id BIGINT REFERENCES companies(id),
    ADD COLUMN IF NOT EXISTS anon_cert BYTEA,
    ADD COLUMN IF NOT EXISTS anon_cert_kid TEXT,
    ADD COLUMN IF NOT EXISTS anon_sig BYTEA,
    ADD COLUMN IF NOT EXISTS anon_ephemeral_pubkey BYTEA;

ALTER TABLE IF EXISTS posts
    ALTER COLUMN author_id DROP NOT NULL;

-- Media: allow NULL owner for anonymous content
ALTER TABLE IF EXISTS media_assets
    ALTER COLUMN owner_id DROP NOT NULL;

-- Backfill principals for existing users
INSERT INTO principals (kind, user_id)
SELECT 'user', id FROM users
ON CONFLICT (user_id) DO NOTHING;

-- Backfill posts with author principal
UPDATE posts p
SET author_principal_id = pr.id
FROM principals pr
WHERE pr.user_id = p.author_id AND p.author_principal_id IS NULL;

ALTER TABLE IF EXISTS posts
    ALTER COLUMN author_principal_id SET NOT NULL;

-- Migrate follows/likes/saves to principal tables
INSERT INTO principal_follows (follower_principal_id, followee_principal_id, created_at)
SELECT p1.id, p2.id, f.created_at
FROM follows f
JOIN principals p1 ON p1.user_id = f.follower_id
JOIN principals p2 ON p2.user_id = f.followee_id
ON CONFLICT DO NOTHING;

INSERT INTO post_likes (liker_principal_id, post_id, created_at)
SELECT p.id, l.post_id, l.created_at
FROM likes l
JOIN principals p ON p.user_id = l.user_id
ON CONFLICT DO NOTHING;

INSERT INTO principal_saved_posts (saver_principal_id, post_id, created_at)
SELECT p.id, s.post_id, s.created_at
FROM saved_posts s
JOIN principals p ON p.user_id = s.user_id
ON CONFLICT DO NOTHING;

-- Legacy tables can be dropped after migration; keep for now to avoid surprise
