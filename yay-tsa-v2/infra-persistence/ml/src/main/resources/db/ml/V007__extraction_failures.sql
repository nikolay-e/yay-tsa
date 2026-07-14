CREATE TABLE core_v2_ml.extraction_failures (
    track_id   UUID        PRIMARY KEY,
    fail_count INTEGER     NOT NULL DEFAULT 1,
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
