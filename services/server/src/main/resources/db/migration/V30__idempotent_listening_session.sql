-- Prevent duplicate active sessions per user
CREATE UNIQUE INDEX idx_listening_session_user_active
    ON listening_session (user_id)
    WHERE ended_at IS NULL;
