CREATE TABLE core_v2_playback.resume_position (
    user_id      VARCHAR(36)  NOT NULL,
    item_id      VARCHAR(36)  NOT NULL,
    position_ms  BIGINT       NOT NULL DEFAULT 0,
    run_time_ms  BIGINT       NOT NULL DEFAULT 0,
    status       VARCHAR(20)  NOT NULL DEFAULT 'in_progress',
    source_event VARCHAR(20)  NOT NULL DEFAULT 'progress',
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, item_id)
);

CREATE INDEX idx_resume_position_user_updated
    ON core_v2_playback.resume_position (user_id, updated_at DESC);
