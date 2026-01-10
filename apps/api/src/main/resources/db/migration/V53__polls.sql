-- Polls (attached to posts)

CREATE TABLE IF NOT EXISTS polls (
    id             BIGSERIAL PRIMARY KEY,
    post_id        BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    question       TEXT NOT NULL,
    max_selections INT NOT NULL,
    closes_at      TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT polls_max_selections_chk CHECK (max_selections >= 1 AND max_selections <= 5),
    CONSTRAINT polls_closes_at_chk CHECK (closes_at IS NULL OR closes_at > created_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_polls_post_id_unique ON polls(post_id);

CREATE TABLE IF NOT EXISTS poll_options (
    id         BIGSERIAL PRIMARY KEY,
    poll_id    BIGINT NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    text       TEXT NOT NULL,
    sort_order INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (poll_id, sort_order)
);

CREATE INDEX IF NOT EXISTS idx_poll_options_poll_id_sort ON poll_options(poll_id, sort_order, id);

CREATE TABLE IF NOT EXISTS poll_votes (
    id           BIGSERIAL PRIMARY KEY,
    poll_id      BIGINT NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    principal_id BIGINT NOT NULL REFERENCES principals(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (poll_id, principal_id)
);

CREATE INDEX IF NOT EXISTS idx_poll_votes_poll_id ON poll_votes(poll_id, id);
CREATE INDEX IF NOT EXISTS idx_poll_votes_principal_id ON poll_votes(principal_id, id);

CREATE TABLE IF NOT EXISTS poll_vote_options (
    vote_id   BIGINT NOT NULL REFERENCES poll_votes(id) ON DELETE CASCADE,
    option_id BIGINT NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
    PRIMARY KEY (vote_id, option_id)
);

CREATE INDEX IF NOT EXISTS idx_poll_vote_options_option_id ON poll_vote_options(option_id);
