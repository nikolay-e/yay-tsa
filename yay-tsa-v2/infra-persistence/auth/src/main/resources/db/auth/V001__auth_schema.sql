CREATE SCHEMA IF NOT EXISTS core_v2_auth;

CREATE TABLE core_v2_auth.users (
    id            UUID         PRIMARY KEY,
    username      citext       NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(255),
    email         citext,
    is_admin      BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT chk_username_length CHECK (length(username) BETWEEN 3 AND 50)
);

CREATE TABLE core_v2_auth.api_tokens (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES core_v2_auth.users(id),
    token       VARCHAR(64)  NOT NULL UNIQUE,
    device_id   VARCHAR(255) NOT NULL,
    device_name VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT uq_user_device UNIQUE (user_id, device_id)
);

CREATE INDEX idx_api_tokens_token_active ON core_v2_auth.api_tokens (token) WHERE revoked = FALSE;
CREATE INDEX idx_api_tokens_user_id ON core_v2_auth.api_tokens (user_id);
CREATE INDEX idx_api_tokens_device_id ON core_v2_auth.api_tokens (device_id);
