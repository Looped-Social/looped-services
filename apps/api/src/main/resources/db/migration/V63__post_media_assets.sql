-- Posts: support multi-photo (max 4) / single-video attachments via join table.
-- The API enforces the "up to 4 photos OR 1 video" rule.

CREATE TABLE IF NOT EXISTS post_media_assets (
  post_id        BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  media_asset_id BIGINT NOT NULL REFERENCES media_assets(id) ON DELETE RESTRICT,
  sort_order     SMALLINT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (post_id, media_asset_id),
  UNIQUE (post_id, sort_order),
  CONSTRAINT chk_post_media_sort_order CHECK (sort_order >= 0 AND sort_order <= 3)
);

CREATE INDEX IF NOT EXISTS idx_post_media_assets_post_sort ON post_media_assets(post_id, sort_order);
