-- Retried Playing/Stopped beacons (network retries, double-clicks, keepalive + foreground flush)
-- otherwise insert a duplicate play_history row, double-counting plays and poisoning the ML/affinity
-- signal. There is no stable client-supplied play-session token on the wire (the PWA, the Jellyfin
-- session DTOs, and the Subsonic /scrobble endpoint all omit one), so a unique natural key is not
-- expressible. Instead we deduplicate within a short rolling window keyed on the server arrival time:
-- a genuine re-listen arrives minutes later (kept), a retry arrives seconds later (suppressed).
-- recorded_at is the server-side arrival instant the dedup window is measured against; it is distinct
-- from started_at, which adapters derive from cached/client clocks and therefore cannot anchor a window.

ALTER TABLE core_v2_playback.play_history
    ADD COLUMN recorded_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_play_history_dedup_window
    ON core_v2_playback.play_history (user_id, item_id, recorded_at DESC);
