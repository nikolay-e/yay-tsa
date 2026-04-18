-- Play statistics: aggregated from play_history, serves as read model for UI
-- Replaces the old play_state table's play_count, last_played_at, playback_position_ms
CREATE TABLE core_v2_playback.play_statistics (
    user_id       VARCHAR(36) NOT NULL,
    item_id       VARCHAR(36) NOT NULL,
    play_count    INTEGER     NOT NULL DEFAULT 0,
    last_played_at TIMESTAMP WITH TIME ZONE,
    playback_position_ms BIGINT NOT NULL DEFAULT 0,
    is_favorite   BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, item_id)
);

CREATE INDEX idx_play_statistics_user_last_played
    ON core_v2_playback.play_statistics (user_id, last_played_at DESC);

CREATE INDEX idx_play_statistics_user_play_count
    ON core_v2_playback.play_statistics (user_id, play_count DESC);
