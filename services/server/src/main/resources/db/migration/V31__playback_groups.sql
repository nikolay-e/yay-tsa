-- Playback groups for multi-device synchronized playback
CREATE TABLE playback_group (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id        UUID NOT NULL REFERENCES users(id),
    listening_session_id UUID NOT NULL REFERENCES listening_session(id),
    canonical_device_id  VARCHAR(255) NOT NULL,
    name                 VARCHAR(100),
    join_code            VARCHAR(16) UNIQUE NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at             TIMESTAMPTZ,
    version              BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_playback_group_owner ON playback_group(owner_user_id) WHERE ended_at IS NULL;
CREATE INDEX idx_playback_group_join_code ON playback_group(join_code) WHERE ended_at IS NULL;
CREATE INDEX idx_playback_group_session ON playback_group(listening_session_id);

-- Group members (devices participating in sync)
CREATE TABLE playback_group_member (
    group_id             UUID NOT NULL REFERENCES playback_group(id) ON DELETE CASCADE,
    device_id            VARCHAR(255) NOT NULL,
    user_id              UUID NOT NULL REFERENCES users(id),
    joined_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_heartbeat_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    stale                BOOLEAN NOT NULL DEFAULT FALSE,
    reported_rtt_ms      INT,
    reported_latency_ms  INT NOT NULL DEFAULT 0,
    PRIMARY KEY (group_id, device_id)
);

-- Playback schedule: the "equation" all clients use to compute position
-- position(now) = anchor_position_ms + (now_server_ms - anchor_server_ms) when !is_paused
CREATE TABLE playback_schedule (
    group_id             UUID PRIMARY KEY REFERENCES playback_group(id) ON DELETE CASCADE,
    track_id             UUID NOT NULL REFERENCES items(id),
    anchor_server_ms     BIGINT NOT NULL,
    anchor_position_ms   BIGINT NOT NULL DEFAULT 0,
    is_paused            BOOLEAN NOT NULL DEFAULT TRUE,
    schedule_epoch       BIGINT NOT NULL DEFAULT 1,
    next_track_id        UUID REFERENCES items(id),
    next_track_anchor_ms BIGINT,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
