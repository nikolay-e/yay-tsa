CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE SCHEMA IF NOT EXISTS core_v2_library;

-- Base entity table (polymorphic root)
CREATE TABLE core_v2_library.entities (
    id            UUID PRIMARY KEY,
    entity_type   VARCHAR(20)  NOT NULL,
    name          VARCHAR(500),
    sort_name     VARCHAR(500),
    parent_id     UUID REFERENCES core_v2_library.entities(id) ON DELETE CASCADE,
    source_path   TEXT UNIQUE,
    container     VARCHAR(50),
    size_bytes    BIGINT,
    mtime         TIMESTAMPTZ,
    library_root  TEXT,
    overview      TEXT,
    search_text   TEXT,
    created_at    TIMESTAMPTZ DEFAULT now(),
    updated_at    TIMESTAMPTZ DEFAULT now()
);

-- Artist extension
CREATE TABLE core_v2_library.artists (
    entity_id      UUID PRIMARY KEY REFERENCES core_v2_library.entities(id) ON DELETE CASCADE,
    musicbrainz_id VARCHAR(36),
    biography      TEXT,
    formed_date    DATE,
    ended_date     DATE
);

-- Album extension
CREATE TABLE core_v2_library.albums (
    entity_id    UUID PRIMARY KEY REFERENCES core_v2_library.entities(id) ON DELETE CASCADE,
    artist_id    UUID REFERENCES core_v2_library.entities(id) ON DELETE SET NULL,
    release_date DATE,
    total_tracks INT,
    total_discs  INT     DEFAULT 1,
    is_complete  BOOLEAN DEFAULT true
);

-- Audio track extension
CREATE TABLE core_v2_library.audio_tracks (
    entity_id                UUID PRIMARY KEY REFERENCES core_v2_library.entities(id) ON DELETE CASCADE,
    album_id                 UUID,
    album_artist_id          UUID,
    track_number             INT,
    disc_number              INT              DEFAULT 1,
    duration_ms              BIGINT,
    bitrate                  INT,
    sample_rate              INT,
    channels                 INT,
    year                     INT,
    codec                    VARCHAR(255),
    comment                  TEXT,
    lyrics                   TEXT,
    fingerprint              TEXT,
    fingerprint_duration     DOUBLE PRECISION,
    fingerprint_sample_rate  INT,
    replaygain_track_gain    NUMERIC(8, 4),
    replaygain_album_gain    NUMERIC(8, 4),
    replaygain_track_peak    NUMERIC(8, 6)
);

-- Genres
CREATE TABLE core_v2_library.genres (
    id   UUID PRIMARY KEY,
    name VARCHAR(255) UNIQUE
);

-- Entity–genre join
CREATE TABLE core_v2_library.entity_genres (
    entity_id UUID REFERENCES core_v2_library.entities(id) ON DELETE CASCADE,
    genre_id  UUID REFERENCES core_v2_library.genres(id)   ON DELETE CASCADE,
    PRIMARY KEY (entity_id, genre_id)
);

-- Images
CREATE TABLE core_v2_library.images (
    id         UUID PRIMARY KEY,
    entity_id  UUID REFERENCES core_v2_library.entities(id) ON DELETE CASCADE,
    image_type VARCHAR(20) NOT NULL,
    path       TEXT        NOT NULL,
    width      INT,
    height     INT,
    size_bytes BIGINT,
    tag        VARCHAR(255),
    is_primary BOOLEAN     DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Trigram indexes for text search
CREATE INDEX idx_entities_name_trgm        ON core_v2_library.entities USING gin (name        gin_trgm_ops);
CREATE INDEX idx_entities_sort_name_trgm   ON core_v2_library.entities USING gin (sort_name   gin_trgm_ops);
CREATE INDEX idx_entities_search_text_trgm ON core_v2_library.entities USING gin (search_text gin_trgm_ops);

-- B-tree indexes
CREATE INDEX idx_entities_type_sort_name   ON core_v2_library.entities (entity_type, sort_name);
CREATE INDEX idx_entities_parent_id        ON core_v2_library.entities (parent_id);
CREATE INDEX idx_audio_tracks_album_id     ON core_v2_library.audio_tracks (album_id);
CREATE INDEX idx_audio_tracks_album_artist ON core_v2_library.audio_tracks (album_artist_id);
CREATE INDEX idx_images_entity_id          ON core_v2_library.images (entity_id);
