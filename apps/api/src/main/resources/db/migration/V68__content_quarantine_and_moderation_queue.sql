-- Content quarantine support (posts/comments) + moderation queue for admin review

ALTER TABLE IF EXISTS posts
    ADD COLUMN IF NOT EXISTS visibility TEXT NOT NULL DEFAULT 'public',
    ADD COLUMN IF NOT EXISTS quarantined_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS quarantine_reason TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'posts_visibility_check'
    ) THEN
        ALTER TABLE posts
            ADD CONSTRAINT posts_visibility_check CHECK (visibility IN ('public','quarantined'));
    END IF;
END$$;

CREATE INDEX IF NOT EXISTS idx_posts_visibility_created_at
    ON posts(visibility, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_posts_quarantined_at
    ON posts(quarantined_at DESC) WHERE quarantined_at IS NOT NULL;

ALTER TABLE IF EXISTS comments
    ADD COLUMN IF NOT EXISTS visibility TEXT NOT NULL DEFAULT 'public',
    ADD COLUMN IF NOT EXISTS quarantined_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS quarantine_reason TEXT,
    ADD COLUMN IF NOT EXISTS removed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS removed_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS removed_reason TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'comments_visibility_check'
    ) THEN
        ALTER TABLE comments
            ADD CONSTRAINT comments_visibility_check CHECK (visibility IN ('public','quarantined'));
    END IF;
END$$;

CREATE INDEX IF NOT EXISTS idx_comments_visibility_created_at
    ON comments(visibility, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_comments_quarantined_at
    ON comments(quarantined_at DESC) WHERE quarantined_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_comments_removed_at
    ON comments(removed_at DESC) WHERE removed_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS moderation_queue_items (
    id BIGSERIAL PRIMARY KEY,
    target_type TEXT NOT NULL, -- 'post' | 'comment'
    target_id BIGINT NOT NULL,
    source TEXT NOT NULL, -- 'blocklist' | 'openai' | 'reports_threshold' | 'manual'
    reason TEXT,
    status TEXT NOT NULL DEFAULT 'open', -- 'open' | 'approved' | 'removed' | 'dismissed'
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    reviewed_by BIGINT REFERENCES admin_users(id) ON DELETE SET NULL,
    review_note TEXT,
    CHECK (target_type IN ('post','comment')),
    CHECK (status IN ('open','approved','removed','dismissed'))
);

CREATE INDEX IF NOT EXISTS idx_moderation_queue_items_status_created_at
    ON moderation_queue_items(status, created_at DESC, id DESC);

-- Only one open queue item per target
CREATE UNIQUE INDEX IF NOT EXISTS uq_moderation_queue_items_open_target
    ON moderation_queue_items(target_type, target_id)
    WHERE status = 'open';

