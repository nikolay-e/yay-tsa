CREATE SCHEMA IF NOT EXISTS core_v2_adaptive;

CREATE TABLE core_v2_adaptive.listening_sessions (
    id               UUID         PRIMARY KEY,
    user_id          UUID         NOT NULL,
    state            VARCHAR(20),
    started_at       TIMESTAMPTZ  NOT NULL,
    last_activity_at TIMESTAMPTZ  NOT NULL,
    ended_at         TIMESTAMPTZ,
    session_summary  TEXT,
    energy           REAL,
    intensity        REAL,
    mood_tags        TEXT[],
    attention_mode   VARCHAR(20)  NOT NULL,
    seed_track_id    UUID,
    seed_genres      TEXT[]
);

CREATE INDEX idx_listening_sessions_user_id ON core_v2_adaptive.listening_sessions (user_id);

CREATE TABLE core_v2_adaptive.adaptive_queue (
    id            UUID         PRIMARY KEY,
    session_id    UUID         NOT NULL REFERENCES core_v2_adaptive.listening_sessions(id) ON DELETE CASCADE,
    track_id      UUID         NOT NULL,
    position      INT          NOT NULL,
    added_reason  TEXT,
    intent_label  VARCHAR(100),
    status        VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    queue_version BIGINT       NOT NULL DEFAULT 1,
    added_at      TIMESTAMPTZ  NOT NULL,
    played_at     TIMESTAMPTZ,
    entity_version BIGINT      NOT NULL DEFAULT 0,

    UNIQUE (session_id, position, queue_version)
);

CREATE TABLE core_v2_adaptive.playback_signals (
    id             UUID         PRIMARY KEY,
    session_id     UUID         NOT NULL REFERENCES core_v2_adaptive.listening_sessions(id) ON DELETE CASCADE,
    track_id       UUID         NOT NULL,
    queue_entry_id UUID,
    signal_type    VARCHAR(30)  NOT NULL,
    context        JSONB,
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_playback_signals_session_id ON core_v2_adaptive.playback_signals (session_id);

CREATE TABLE core_v2_adaptive.llm_decisions (
    id                     UUID         PRIMARY KEY,
    session_id             UUID         NOT NULL REFERENCES core_v2_adaptive.listening_sessions(id) ON DELETE CASCADE,
    trigger_type           VARCHAR(30)  NOT NULL,
    trigger_signal_id      UUID,
    prompt_hash            VARCHAR(64),
    prompt_tokens          INT,
    completion_tokens      INT,
    model_id               VARCHAR(50),
    latency_ms             INT,
    intent                 JSONB,
    edits                  JSONB,
    base_queue_version     BIGINT,
    applied_queue_version  BIGINT,
    validation_result      VARCHAR(20),
    validation_details     JSONB,
    created_at             TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_llm_decisions_session_id ON core_v2_adaptive.llm_decisions (session_id);
