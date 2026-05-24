CREATE TABLE core_v2_groups.playback_group (
    id         UUID         PRIMARY KEY,
    owner_id   UUID         NOT NULL,
    name       VARCHAR(200) NOT NULL,
    join_code  VARCHAR(12)  NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE core_v2_groups.playback_group_member (
    group_id            UUID         NOT NULL REFERENCES core_v2_groups.playback_group(id) ON DELETE CASCADE,
    device_id           VARCHAR(255) NOT NULL,
    user_id             UUID         NOT NULL,
    reported_latency_ms INTEGER      NOT NULL DEFAULT 0,
    last_seen_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, device_id)
);

CREATE TABLE core_v2_groups.playback_schedule (
    group_id           UUID        PRIMARY KEY REFERENCES core_v2_groups.playback_group(id) ON DELETE CASCADE,
    track_id           UUID,
    anchor_server_ms   BIGINT      NOT NULL,
    anchor_position_ms BIGINT      NOT NULL DEFAULT 0,
    is_paused          BOOLEAN     NOT NULL DEFAULT TRUE,
    schedule_epoch     BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_group_member_user ON core_v2_groups.playback_group_member (user_id);
