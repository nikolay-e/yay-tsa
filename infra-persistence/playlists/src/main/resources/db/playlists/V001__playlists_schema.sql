CREATE SCHEMA IF NOT EXISTS core_v2_playlists;

CREATE TABLE core_v2_playlists.playlists (
    id          VARCHAR(36)  PRIMARY KEY,
    owner       VARCHAR(36)  NOT NULL,
    name        VARCHAR(500) NOT NULL,
    description TEXT,
    is_public   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE core_v2_playlists.playlist_tracks (
    playlist_id VARCHAR(36)  NOT NULL,
    track_id    VARCHAR(36)  NOT NULL,
    position    INT          NOT NULL,
    added_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (playlist_id, position),
    CONSTRAINT fk_playlist_tracks_playlist
        FOREIGN KEY (playlist_id) REFERENCES core_v2_playlists.playlists(id) ON DELETE CASCADE
);

CREATE INDEX idx_playlists_owner ON core_v2_playlists.playlists (owner);
