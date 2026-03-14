CREATE TABLE user_track_affinity (
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    track_id       UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    affinity_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    play_count     INT NOT NULL DEFAULT 0,
    completion_count INT NOT NULL DEFAULT 0,
    skip_count     INT NOT NULL DEFAULT 0,
    thumbs_up_count INT NOT NULL DEFAULT 0,
    thumbs_down_count INT NOT NULL DEFAULT 0,
    total_listen_sec INT NOT NULL DEFAULT 0,
    last_signal_at TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, track_id)
);

CREATE INDEX idx_uta_user_affinity ON user_track_affinity (user_id, affinity_score DESC);

ALTER TABLE taste_profile
    ADD COLUMN embedding_mert vector(768),
    ADD COLUMN embedding_clap vector(512),
    ADD COLUMN track_count INT NOT NULL DEFAULT 0;
