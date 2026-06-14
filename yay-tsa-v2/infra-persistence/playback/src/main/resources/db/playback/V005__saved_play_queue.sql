-- Subsonic savePlayQueue/getPlayQueue store a per-user queue SNAPSHOT for cross-client resume,
-- not a mutation of the live single-writer playback session. It is intentionally decoupled from
-- PlaybackSessionAggregate, the device lease, and OCC: it is a last-writer-wins per-user store
-- (mirrors Navidrome's playqueue table). One row per user; track order is preserved in the jsonb array.

CREATE TABLE core_v2_playback.saved_play_queue (
    user_id          VARCHAR(36)  NOT NULL,
    track_ids        TEXT[]       NOT NULL DEFAULT '{}',
    current_track_id VARCHAR(36),
    position_ms      BIGINT       NOT NULL DEFAULT 0,
    changed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    changed_by       VARCHAR(255),
    PRIMARY KEY (user_id)
);
