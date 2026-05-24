ALTER TABLE core_v2_karaoke.assets
    ADD COLUMN fail_count     INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN last_failed_at TIMESTAMPTZ,
    ADD COLUMN last_error     TEXT;

CREATE INDEX idx_karaoke_assets_retryable
    ON core_v2_karaoke.assets (fail_count)
    WHERE ready_at IS NULL;
