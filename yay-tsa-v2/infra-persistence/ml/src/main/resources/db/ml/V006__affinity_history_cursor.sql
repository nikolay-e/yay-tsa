-- Affinity was folded exclusively from core_v2_adaptive.playback_signals, which are emitted only
-- during an active adaptive/DJ session. Normal (non-DJ) listening — the bulk of play history —
-- therefore never influenced affinity, so the taste model learned from a biased sample.
--
-- Add a second, independent watermark so the aggregator can also fold core_v2_playback.play_history
-- (all listening) WITHOUT double-counting the adaptive plays already covered by signals: the history
-- fold consumes only rows whose source is NOT 'adaptive' (self-picked queues, other protocols, and
-- legacy pre-source rows), while the signals fold keeps owning adaptive-session events. The two
-- sources are disjoint, so no truncate/replay is needed — the history fold adds non-adaptive
-- listening on top of the existing signal-derived affinity.
ALTER TABLE core_v2_ml.affinity_cursor
    ADD COLUMN last_history_at TIMESTAMPTZ NOT NULL DEFAULT 'epoch';
