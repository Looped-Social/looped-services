-- Community-specific bans (and all-communities bans) for users.

CREATE TABLE IF NOT EXISTS user_community_bans (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope TEXT NOT NULL DEFAULT 'community', -- 'community' or 'all_communities'
    community_id BIGINT REFERENCES communities(id) ON DELETE CASCADE,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    created_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    CHECK (scope IN ('community', 'all_communities')),
    CHECK (
        (scope = 'community' AND community_id IS NOT NULL)
        OR
        (scope = 'all_communities' AND community_id IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_user_community_bans_user_active
    ON user_community_bans(user_id, revoked_at, expires_at);

CREATE INDEX IF NOT EXISTS idx_user_community_bans_user_community_active
    ON user_community_bans(user_id, community_id, revoked_at, expires_at);

