-- Device presence for remote control
ALTER TABLE sessions ADD COLUMN is_online BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sessions ADD COLUMN last_heartbeat_at TIMESTAMPTZ;

CREATE INDEX idx_sessions_user_online ON sessions(user_id) WHERE is_online = TRUE;
