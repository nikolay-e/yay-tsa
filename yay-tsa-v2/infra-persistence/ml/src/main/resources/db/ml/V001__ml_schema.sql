CREATE SCHEMA IF NOT EXISTS core_v2_ml;

CREATE TABLE core_v2_ml.track_features (
    track_id             UUID         PRIMARY KEY,
    bpm                  REAL,
    bpm_confidence       REAL,
    musical_key          VARCHAR(20),
    key_confidence       REAL,
    energy               REAL,
    loudness_integrated  REAL,
    loudness_range       REAL,
    average_loudness     REAL,
    valence              REAL,
    arousal              REAL,
    danceability         REAL,
    vocal_instrumental   REAL,
    voice_gender         VARCHAR(10),
    spectral_complexity  REAL,
    dissonance           REAL,
    onset_rate           REAL,
    intro_duration_sec   REAL,
    embedding_discogs    FLOAT4[],
    embedding_musicnn    FLOAT4[],
    embedding_clap       FLOAT4[],
    embedding_mert       FLOAT4[],
    extracted_at         TIMESTAMPTZ  NOT NULL,
    extractor_version    VARCHAR(20)  NOT NULL
);

CREATE TABLE core_v2_ml.taste_profiles (
    user_id        UUID         PRIMARY KEY,
    profile        TEXT,
    summary_text   TEXT,
    rebuilt_at     TIMESTAMPTZ  NOT NULL,
    track_count    INT          NOT NULL DEFAULT 0,
    embedding_mert FLOAT4[],
    embedding_clap FLOAT4[]
);

CREATE TABLE core_v2_ml.user_track_affinity (
    user_id          UUID             NOT NULL,
    track_id         UUID             NOT NULL,
    affinity_score   DOUBLE PRECISION NOT NULL DEFAULT 0,
    play_count       INT              NOT NULL DEFAULT 0,
    completion_count INT              NOT NULL DEFAULT 0,
    skip_count       INT              NOT NULL DEFAULT 0,
    thumbs_up_count  INT              NOT NULL DEFAULT 0,
    thumbs_down_count INT             NOT NULL DEFAULT 0,
    total_listen_sec INT              NOT NULL DEFAULT 0,
    last_signal_at   TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ      NOT NULL,

    PRIMARY KEY (user_id, track_id)
);

CREATE INDEX idx_user_track_affinity_user_id ON core_v2_ml.user_track_affinity (user_id);
