-- Channel group photo + per-channel preferences (mute)

ALTER TABLE IF EXISTS channels
    ADD COLUMN IF NOT EXISTS photo_media_asset_id BIGINT REFERENCES media_assets(id) ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS channel_preferences (
    channel_id BIGINT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    muted BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_channel_preferences_user ON channel_preferences(user_id);

