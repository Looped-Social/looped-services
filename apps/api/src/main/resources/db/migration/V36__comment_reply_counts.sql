-- Comment reply counts

ALTER TABLE IF EXISTS comments
    ADD COLUMN IF NOT EXISTS reply_count INT NOT NULL DEFAULT 0;

WITH counts AS (
    SELECT parent_id, COUNT(*) AS cnt
    FROM comments
    WHERE parent_id IS NOT NULL
    GROUP BY parent_id
)
UPDATE comments c
SET reply_count = counts.cnt
FROM counts
WHERE c.id = counts.parent_id;
