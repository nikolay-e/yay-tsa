CREATE TABLE radio_seed_cache (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    position    SMALLINT NOT NULL,
    track_id    UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, position)
);

CREATE INDEX idx_radio_seed_cache_user ON radio_seed_cache(user_id);
