-- The dedup guard moved from a recorded_at (server-arrival) window to a started_at window keyed on
-- (user_id, item_id, started_at ± window) with a duration tolerance. Reason: the same play is
-- reported by multiple, dedup-unaware paths (Jellyfin /Sessions/Playing/Stopped, Subsonic /scrobble)
-- that derive different started_at values and can arrive minutes apart in server time. A window on
-- recorded_at therefore missed those phantom duplicates (same track, shifted started_at, ~100%
-- completion), inflating exactly the replay signal the DJ trusts. Anchoring on started_at collapses
-- them: a genuine re-listen carries a started_at outside the window, a phantom carries one inside it.
-- This index backs the NOT EXISTS probe (user_id, item_id, started_at range scan).
CREATE INDEX IF NOT EXISTS idx_play_history_started_at_dedup
    ON core_v2_playback.play_history (user_id, item_id, started_at);
