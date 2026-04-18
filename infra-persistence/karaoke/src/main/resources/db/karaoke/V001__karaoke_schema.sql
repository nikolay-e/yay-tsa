CREATE SCHEMA IF NOT EXISTS core_v2_karaoke;

CREATE TABLE core_v2_karaoke.assets (
    track_id          UUID        PRIMARY KEY,
    instrumental_path TEXT,
    vocal_path        TEXT,
    lyrics_timing     TEXT,
    ready_at          TIMESTAMPTZ
);
