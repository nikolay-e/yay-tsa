CREATE SCHEMA IF NOT EXISTS core_v2_preferences;

CREATE TABLE core_v2_preferences.user_preferences (
    user_id VARCHAR(36) PRIMARY KEY,
    version BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE core_v2_preferences.favorites (
    user_id      VARCHAR(36)  NOT NULL,
    track_id     VARCHAR(36)  NOT NULL,
    favorited_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    position     INT          NOT NULL,

    PRIMARY KEY (user_id, track_id)
);

CREATE TABLE core_v2_preferences.preference_contracts (
    user_id    VARCHAR(36) PRIMARY KEY,
    hard_rules TEXT,
    soft_prefs TEXT,
    dj_style   TEXT,
    red_lines  TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
