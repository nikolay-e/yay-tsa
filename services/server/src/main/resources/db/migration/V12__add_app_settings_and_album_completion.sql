-- Application settings stored as key-value pairs
CREATE TABLE app_settings (
    key TEXT PRIMARY KEY,
    value TEXT,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Album completion tracking
ALTER TABLE albums ADD COLUMN is_complete BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_albums_is_complete ON albums(is_complete) WHERE is_complete = FALSE;
