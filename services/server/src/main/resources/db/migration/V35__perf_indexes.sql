CREATE INDEX IF NOT EXISTS idx_play_state_user_favorite
    ON play_state (user_id, is_favorite)
    WHERE is_favorite = true;

CREATE INDEX IF NOT EXISTS idx_play_history_user_item_started
    ON play_history (user_id, item_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_sessions_last_heartbeat
    ON sessions (last_heartbeat_at)
    WHERE last_heartbeat_at IS NOT NULL;
