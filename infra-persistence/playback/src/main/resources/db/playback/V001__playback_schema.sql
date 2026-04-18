CREATE SCHEMA IF NOT EXISTS core_v2_playback;

CREATE TABLE core_v2_playback.playback_sessions (
    user_id                VARCHAR(36)  NOT NULL,
    session_id             VARCHAR(36)  NOT NULL,
    current_entry_id       VARCHAR(36),
    playback_state         VARCHAR(20)  NOT NULL DEFAULT 'STOPPED',
    last_known_position_ms BIGINT       NOT NULL DEFAULT 0,
    last_known_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lease_owner            VARCHAR(36),
    lease_expires_at       TIMESTAMPTZ,
    version                BIGINT       NOT NULL DEFAULT 0,

    PRIMARY KEY (user_id, session_id)
);

CREATE TABLE core_v2_playback.queue_entries (
    user_id    VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    entry_id   VARCHAR(36) NOT NULL,
    track_id   VARCHAR(36) NOT NULL,
    position   INT         NOT NULL,

    PRIMARY KEY (user_id, session_id, entry_id),
    CONSTRAINT fk_queue_entries_session
        FOREIGN KEY (user_id, session_id)
        REFERENCES core_v2_playback.playback_sessions(user_id, session_id) ON DELETE CASCADE
);

CREATE TABLE core_v2_playback.play_history (
    id         UUID        PRIMARY KEY,
    user_id    VARCHAR(36) NOT NULL,
    item_id    VARCHAR(36) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    duration_ms BIGINT     NOT NULL,
    played_ms  BIGINT      NOT NULL DEFAULT 0,
    completed  BOOLEAN     NOT NULL DEFAULT FALSE,
    scrobbled  BOOLEAN     NOT NULL DEFAULT FALSE,
    skipped    BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_play_history_user_id ON core_v2_playback.play_history (user_id);
