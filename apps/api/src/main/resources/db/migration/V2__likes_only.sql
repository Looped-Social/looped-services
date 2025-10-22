-- Rename reactions to likes and posts.reactions_count to likes_count

ALTER TABLE IF EXISTS posts
    RENAME COLUMN reactions_count TO likes_count;

ALTER TABLE IF EXISTS reactions
    RENAME TO likes;

-- Drop generic type column; we only support 'like'
ALTER TABLE IF EXISTS likes
    DROP COLUMN IF EXISTS type;

