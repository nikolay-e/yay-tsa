ALTER TABLE core_v2_library.albums ADD COLUMN musicbrainz_id VARCHAR(36);
ALTER TABLE core_v2_library.albums ADD COLUMN release_group_mbid VARCHAR(36);
ALTER TABLE core_v2_library.albums ADD COLUMN metadata_checked_at TIMESTAMPTZ;
ALTER TABLE core_v2_library.artists ADD COLUMN metadata_checked_at TIMESTAMPTZ;
CREATE INDEX idx_albums_metadata_unchecked ON core_v2_library.albums (metadata_checked_at) WHERE metadata_checked_at IS NULL;
CREATE INDEX idx_artists_metadata_unchecked ON core_v2_library.artists (metadata_checked_at) WHERE metadata_checked_at IS NULL;
