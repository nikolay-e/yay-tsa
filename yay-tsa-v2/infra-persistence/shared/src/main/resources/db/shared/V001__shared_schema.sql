CREATE SCHEMA IF NOT EXISTS core_v2_shared;

CREATE TABLE core_v2_shared.idempotency_records (
    user_id      VARCHAR(36) NOT NULL,
    command_type VARCHAR(500) NOT NULL,
    idem_key     VARCHAR(500) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    result_version BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, command_type, idem_key)
);

CREATE INDEX idx_idempotency_created ON core_v2_shared.idempotency_records(created_at);

CREATE TABLE core_v2_shared.outbox (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    context      VARCHAR(50) NOT NULL,
    payload      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON core_v2_shared.outbox(created_at) WHERE published_at IS NULL;
