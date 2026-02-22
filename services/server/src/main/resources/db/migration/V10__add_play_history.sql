CREATE TABLE play_history (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_id     UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    started_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_ms BIGINT NOT NULL,
    played_ms   BIGINT NOT NULL DEFAULT 0,
    completed   BOOLEAN NOT NULL DEFAULT FALSE,
    scrobbled   BOOLEAN NOT NULL DEFAULT FALSE,
    skipped     BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT play_history_played_ms_non_negative CHECK (played_ms >= 0),
    CONSTRAINT play_history_duration_ms_positive CHECK (duration_ms > 0)
);

CREATE INDEX idx_play_history_user_recent
    ON play_history (user_id, started_at DESC);

CREATE INDEX idx_play_history_scrobbles
    ON play_history (user_id, item_id)
    WHERE scrobbled = TRUE;

ALTER TABLE sessions ADD COLUMN playback_started_at TIMESTAMP WITH TIME ZONE;
