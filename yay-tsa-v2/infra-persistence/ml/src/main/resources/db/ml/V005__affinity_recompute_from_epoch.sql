-- V004 introduced affinity_cursor seeded at 'epoch' but did not empty
-- user_track_affinity, which the prior wall-clock-watermark aggregator had already
-- populated. The first signal-time-cursor run therefore re-folded all historical
-- signals additively onto existing rows, inflating the cumulative counters
-- (sum(play_count) diverged from the PLAY_START signal count). user_track_affinity is
-- a regenerable projection of core_v2_adaptive.playback_signals (the authoritative
-- event log), so the cursor at 'epoch' is only consistent with an empty table.
-- Truncate the projection and replay from epoch so every signal folds exactly once.
TRUNCATE core_v2_ml.user_track_affinity;

UPDATE core_v2_ml.affinity_cursor
SET last_signal_at = 'epoch',
    updated_at = now()
WHERE id = TRUE;
