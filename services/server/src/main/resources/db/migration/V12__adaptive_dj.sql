CREATE EXTENSION IF NOT EXISTS vector;

-- Track audio features (ML-extracted at import time)
CREATE TABLE track_features (
    track_id UUID PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,

    bpm REAL,
    bpm_confidence REAL,
    musical_key VARCHAR(20),
    key_confidence REAL,

    energy REAL,
    loudness_integrated REAL,
    loudness_range REAL,
    average_loudness REAL,

    valence REAL,
    arousal REAL,

    danceability REAL,
    vocal_instrumental REAL,
    voice_gender VARCHAR(10),
    spectral_complexity REAL,
    dissonance REAL,
    onset_rate REAL,
    intro_duration_sec REAL,

    embedding_discogs vector(1280),
    embedding_musicnn vector(200),

    extracted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    extractor_version VARCHAR(20) NOT NULL DEFAULT '1.0'
);

CREATE INDEX idx_tf_discogs_hnsw ON track_features
    USING hnsw (embedding_discogs vector_cosine_ops)
    WITH (m = 16, ef_construction = 128);

CREATE INDEX idx_tf_musicnn_hnsw ON track_features
    USING hnsw (embedding_musicnn vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX idx_tf_bpm ON track_features(bpm);
CREATE INDEX idx_tf_energy ON track_features(energy);
CREATE INDEX idx_tf_valence ON track_features(valence);
CREATE INDEX idx_tf_arousal ON track_features(arousal);

-- Feature extraction job queue
CREATE TABLE feature_extraction_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_fej_pending ON feature_extraction_job(status, created_at)
    WHERE status = 'PENDING';

-- User preference contract
CREATE TABLE user_preference_contract (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    hard_rules JSONB NOT NULL DEFAULT '{}'::jsonb,
    soft_prefs JSONB NOT NULL DEFAULT '{}'::jsonb,
    dj_style JSONB NOT NULL DEFAULT '{}'::jsonb,
    red_lines JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Listening session
CREATE TABLE listening_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    state JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at TIMESTAMPTZ,
    session_summary TEXT,
    CONSTRAINT chk_session_active CHECK (ended_at IS NULL OR ended_at > started_at)
);

CREATE INDEX idx_ls_user_active ON listening_session(user_id, started_at DESC)
    WHERE ended_at IS NULL;

-- Adaptive queue (versioned)
CREATE TABLE adaptive_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES listening_session(id) ON DELETE CASCADE,
    track_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    position INT NOT NULL,
    added_reason TEXT,
    intent_label VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    queue_version BIGINT NOT NULL DEFAULT 1,
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    played_at TIMESTAMPTZ,
    UNIQUE(session_id, position, queue_version)
);

CREATE INDEX idx_aq_session_pos ON adaptive_queue(session_id, position)
    WHERE status IN ('QUEUED', 'PLAYING');

-- Playback signals
CREATE TABLE playback_signal (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES listening_session(id) ON DELETE CASCADE,
    track_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    queue_entry_id UUID REFERENCES adaptive_queue(id),
    signal_type VARCHAR(30) NOT NULL,
    context JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ps_session_time ON playback_signal(session_id, created_at DESC);
CREATE INDEX idx_ps_track ON playback_signal(track_id, created_at DESC);

-- LLM decision log
CREATE TABLE llm_decision_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES listening_session(id) ON DELETE CASCADE,
    trigger_type VARCHAR(30) NOT NULL,
    trigger_signal_id UUID REFERENCES playback_signal(id),
    prompt_hash VARCHAR(64),
    prompt_tokens INT,
    completion_tokens INT,
    model_id VARCHAR(50),
    latency_ms INT,
    intent JSONB,
    edits JSONB,
    base_queue_version BIGINT,
    applied_queue_version BIGINT,
    validation_result VARCHAR(20),
    validation_details JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ldl_session ON llm_decision_log(session_id, created_at DESC);

-- Taste profile (aggregated from history)
CREATE TABLE taste_profile (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    profile JSONB NOT NULL DEFAULT '{}'::jsonb,
    summary_text TEXT,
    rebuilt_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
