-- Communities (company, school, sector) and community-scoped posts

CREATE TABLE IF NOT EXISTS communities (
    id           BIGSERIAL PRIMARY KEY,
    kind         TEXT NOT NULL, -- company | school | sector
    name         TEXT NOT NULL,
    description  TEXT,
    member_count INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (kind, name)
);
CREATE INDEX IF NOT EXISTS idx_communities_kind_name ON communities(kind, lower(name));
CREATE INDEX IF NOT EXISTS idx_communities_created ON communities(created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS community_follows (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    is_pinned    BOOLEAN NOT NULL DEFAULT false,
    sort_order   INT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, community_id)
);
CREATE INDEX IF NOT EXISTS idx_community_follows_user_created ON community_follows(user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_community_follows_community ON community_follows(community_id);

CREATE TABLE IF NOT EXISTS community_verifications (
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    method       TEXT NOT NULL,
    verified     BOOLEAN NOT NULL DEFAULT false,
    verified_at  TIMESTAMPTZ,
    PRIMARY KEY (user_id, community_id)
);

ALTER TABLE IF EXISTS posts
    ADD COLUMN IF NOT EXISTS community_id BIGINT REFERENCES communities(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_posts_community_created_at
    ON posts(community_id, created_at DESC, id DESC);
