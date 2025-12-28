-- Comment principals + anon-aware likes

ALTER TABLE IF EXISTS comments
    ADD COLUMN IF NOT EXISTS author_principal_id BIGINT REFERENCES principals(id);

ALTER TABLE IF EXISTS comments
    ALTER COLUMN user_id DROP NOT NULL;

UPDATE comments c
SET author_principal_id = p.id
FROM principals p
WHERE p.user_id = c.user_id AND c.author_principal_id IS NULL;

ALTER TABLE IF EXISTS comments
    ALTER COLUMN author_principal_id SET NOT NULL;

ALTER TABLE IF EXISTS comment_likes
    ADD COLUMN IF NOT EXISTS liker_principal_id BIGINT REFERENCES principals(id);

ALTER TABLE IF EXISTS comment_likes
    ALTER COLUMN user_id DROP NOT NULL;

UPDATE comment_likes cl
SET liker_principal_id = p.id
FROM principals p
WHERE p.user_id = cl.user_id AND cl.liker_principal_id IS NULL;

ALTER TABLE IF EXISTS comment_likes
    ALTER COLUMN liker_principal_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_comment_likes_comment_principal
    ON comment_likes(comment_id, liker_principal_id);

CREATE INDEX IF NOT EXISTS idx_comment_likes_principal_created
    ON comment_likes(liker_principal_id, created_at DESC, id DESC);
