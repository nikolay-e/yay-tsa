CREATE TABLE track_audio_features (
    item_id      UUID PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
    mood         VARCHAR(50),
    energy       SMALLINT CHECK (energy BETWEEN 1 AND 10),
    language     VARCHAR(10),
    themes       TEXT,
    valence      SMALLINT CHECK (valence BETWEEN 1 AND 10),
    danceability SMALLINT CHECK (danceability BETWEEN 1 AND 10),
    analyzed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    llm_provider VARCHAR(50),
    llm_model    VARCHAR(100),
    raw_response TEXT
);

CREATE INDEX idx_taf_mood ON track_audio_features(mood);
CREATE INDEX idx_taf_energy ON track_audio_features(energy);
CREATE INDEX idx_taf_language ON track_audio_features(language);
CREATE INDEX idx_taf_composite ON track_audio_features(mood, energy, language);
