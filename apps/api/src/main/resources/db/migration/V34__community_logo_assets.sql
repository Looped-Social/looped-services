-- Community logo assets

CREATE TABLE IF NOT EXISTS community_logo_assets (
  id BIGSERIAL PRIMARY KEY,
  community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
  media_asset_id BIGINT NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (community_id, media_asset_id),
  UNIQUE (media_asset_id)
);

CREATE INDEX IF NOT EXISTS idx_community_logo_assets_community
  ON community_logo_assets(community_id);
