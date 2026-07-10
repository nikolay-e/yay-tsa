-- Scrobble provenance: which surface produced the play (an active adaptive/DJ session,
-- a subsonic client, ...) and which device reported it. Skips inside a radio session are
-- exploration; skips in a self-picked queue are real downgrades — aggregates must be able
-- to condition on the source. Both columns nullable: historical rows stay NULL and are
-- rendered as an "unknown" bucket, never guessed.
ALTER TABLE core_v2_playback.play_history
    ADD COLUMN source    VARCHAR(16),
    ADD COLUMN device_id VARCHAR(64);

-- History window/page queries filter on user_id + started_at range; the existing indexes
-- cover only (user_id) and the (user_id, item_id, recorded_at) dedup probe.
CREATE INDEX idx_play_history_user_started_at
    ON core_v2_playback.play_history (user_id, started_at DESC);
