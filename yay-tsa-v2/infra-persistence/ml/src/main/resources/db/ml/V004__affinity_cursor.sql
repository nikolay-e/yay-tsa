-- Incremental watermark for the affinity aggregator, derived from signal emit-time
-- (playback_signals.created_at) rather than aggregation wall-clock (now()).
-- A single row holds the created_at of the last signal folded into user_track_affinity;
-- the next run resumes at `WHERE ps.created_at > last_signal_at`, so the watermark and
-- the filter share one clock and no signal can be skipped between runs.
CREATE TABLE core_v2_ml.affinity_cursor (
    id              BOOLEAN     PRIMARY KEY DEFAULT TRUE,
    last_signal_at  TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT affinity_cursor_singleton CHECK (id)
);

INSERT INTO core_v2_ml.affinity_cursor (id, last_signal_at, updated_at)
VALUES (TRUE, 'epoch', now());
