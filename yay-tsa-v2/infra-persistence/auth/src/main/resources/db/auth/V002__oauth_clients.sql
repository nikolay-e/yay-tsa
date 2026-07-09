CREATE TABLE core_v2_auth.oauth_clients (
    id            UUID         PRIMARY KEY,
    client_name   VARCHAR(255) NOT NULL,
    redirect_uris TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
