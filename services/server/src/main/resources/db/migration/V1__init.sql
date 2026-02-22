-- Yaytsa Media Server Database Schema
-- Initial migration for PostgreSQL 15+

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "citext";
CREATE EXTENSION IF NOT EXISTS "pg_trgm"; -- For text search

-- Create custom types
CREATE TYPE item_type AS ENUM ('AudioTrack', 'MusicAlbum', 'MusicArtist', 'Folder', 'Playlist');
CREATE TYPE image_type AS ENUM ('Primary', 'Art', 'Backdrop', 'Banner', 'Logo', 'Thumb', 'Disc', 'Box', 'Screenshot', 'Menu', 'Chapter');

-- Users table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username CITEXT UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    email CITEXT,
    is_admin BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT username_length CHECK (char_length(username) >= 3 AND char_length(username) <= 50)
);

CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_email ON users(email);

-- API Tokens table for authentication
CREATE TABLE api_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(64) UNIQUE NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    device_name VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    revoked BOOLEAN DEFAULT FALSE,
    CONSTRAINT token_user_device_unique UNIQUE (user_id, device_id)
);

CREATE INDEX idx_api_tokens_token ON api_tokens(token) WHERE revoked = FALSE;
CREATE INDEX idx_api_tokens_user_id ON api_tokens(user_id);
CREATE INDEX idx_api_tokens_device_id ON api_tokens(device_id);

-- Items table (base table for all media items)
CREATE TABLE items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    type item_type NOT NULL,
    name VARCHAR(500) NOT NULL,
    sort_name VARCHAR(500),
    parent_id UUID,
    path TEXT UNIQUE,
    container VARCHAR(50),
    size_bytes BIGINT,
    mtime TIMESTAMP WITH TIME ZONE,
    library_root TEXT,
    overview TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_parent FOREIGN KEY (parent_id) REFERENCES items(id) ON DELETE CASCADE
);

CREATE INDEX idx_items_type ON items(type);
CREATE INDEX idx_items_parent_id ON items(parent_id);
CREATE INDEX idx_items_sort_name ON items(sort_name);
CREATE INDEX idx_items_path ON items(path);
CREATE INDEX idx_items_name_trgm ON items USING gin (name gin_trgm_ops); -- For fuzzy search
CREATE INDEX idx_items_sort_name_trgm ON items USING gin (sort_name gin_trgm_ops); -- For fuzzy search

-- Audio tracks table
CREATE TABLE audio_tracks (
    item_id UUID PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
    album_id UUID REFERENCES items(id) ON DELETE SET NULL,
    album_artist_id UUID REFERENCES items(id) ON DELETE SET NULL,
    track_number INTEGER,
    disc_number INTEGER DEFAULT 1,
    duration_ms BIGINT,
    bitrate INTEGER,
    sample_rate INTEGER,
    channels INTEGER,
    year INTEGER,
    codec VARCHAR(50),
    comment TEXT,
    lyrics TEXT,
    CONSTRAINT track_number_positive CHECK (track_number IS NULL OR track_number > 0),
    CONSTRAINT disc_number_positive CHECK (disc_number IS NULL OR disc_number > 0),
    CONSTRAINT duration_positive CHECK (duration_ms IS NULL OR duration_ms >= 0)
);

CREATE INDEX idx_audio_tracks_album_id ON audio_tracks(album_id);
CREATE INDEX idx_audio_tracks_album_artist_id ON audio_tracks(album_artist_id);
CREATE INDEX idx_audio_tracks_year ON audio_tracks(year);

-- Albums table
CREATE TABLE albums (
    item_id UUID PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
    artist_id UUID REFERENCES items(id) ON DELETE SET NULL,
    release_date DATE,
    total_tracks INTEGER,
    total_discs INTEGER DEFAULT 1,
    CONSTRAINT total_tracks_positive CHECK (total_tracks IS NULL OR total_tracks > 0),
    CONSTRAINT total_discs_positive CHECK (total_discs IS NULL OR total_discs > 0)
);

CREATE INDEX idx_albums_artist_id ON albums(artist_id);
CREATE INDEX idx_albums_release_date ON albums(release_date);

-- Artists table
CREATE TABLE artists (
    item_id UUID PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
    musicbrainz_id VARCHAR(36),
    biography TEXT,
    formed_date DATE,
    ended_date DATE
);

CREATE INDEX idx_artists_musicbrainz_id ON artists(musicbrainz_id);

-- Genres table
CREATE TABLE genres (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) UNIQUE NOT NULL
);

CREATE INDEX idx_genres_name ON genres(name);

-- Item-Genre many-to-many relationship
CREATE TABLE item_genres (
    item_id UUID REFERENCES items(id) ON DELETE CASCADE,
    genre_id UUID REFERENCES genres(id) ON DELETE CASCADE,
    PRIMARY KEY (item_id, genre_id)
);

CREATE INDEX idx_item_genres_genre_id ON item_genres(genre_id);
CREATE INDEX idx_item_genres_item_id ON item_genres(item_id);

-- Images table
CREATE TABLE images (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    item_id UUID REFERENCES items(id) ON DELETE CASCADE,
    type image_type NOT NULL,
    path TEXT NOT NULL,
    width INTEGER,
    height INTEGER,
    size_bytes BIGINT,
    tag VARCHAR(255), -- For cache busting
    is_primary BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_images_item_id ON images(item_id);
CREATE INDEX idx_images_type ON images(type);
CREATE INDEX idx_images_tag ON images(tag);

-- Playlists table
CREATE TABLE playlists (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    is_public BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_playlists_user_id ON playlists(user_id);
CREATE INDEX idx_playlists_is_public ON playlists(is_public);

-- Playlist entries table
CREATE TABLE playlist_entries (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    playlist_id UUID REFERENCES playlists(id) ON DELETE CASCADE,
    item_id UUID REFERENCES items(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    added_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_playlist_position UNIQUE (playlist_id, position),
    CONSTRAINT position_positive CHECK (position >= 0)
);

CREATE INDEX idx_playlist_entries_playlist_id ON playlist_entries(playlist_id);
CREATE INDEX idx_playlist_entries_item_id ON playlist_entries(item_id);
CREATE INDEX idx_playlist_entries_position ON playlist_entries(playlist_id, position);

-- Play state table (user-specific item state)
CREATE TABLE play_state (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    item_id UUID REFERENCES items(id) ON DELETE CASCADE,
    is_favorite BOOLEAN DEFAULT FALSE,
    play_count INTEGER DEFAULT 0,
    last_played_at TIMESTAMP WITH TIME ZONE,
    playback_position_ms BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_user_item UNIQUE (user_id, item_id),
    CONSTRAINT play_count_non_negative CHECK (play_count >= 0),
    CONSTRAINT playback_position_non_negative CHECK (playback_position_ms >= 0)
);

CREATE INDEX idx_play_state_user_id ON play_state(user_id);
CREATE INDEX idx_play_state_item_id ON play_state(item_id);
CREATE INDEX idx_play_state_is_favorite ON play_state(user_id, is_favorite);
CREATE INDEX idx_play_state_last_played ON play_state(user_id, last_played_at DESC);

-- Sessions table (active playback sessions)
CREATE TABLE sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    device_id VARCHAR(255) NOT NULL,
    device_name VARCHAR(255),
    client_name VARCHAR(255),
    client_version VARCHAR(50),
    now_playing_item_id UUID REFERENCES items(id) ON DELETE SET NULL,
    position_ms BIGINT DEFAULT 0,
    paused BOOLEAN DEFAULT FALSE,
    volume_level INTEGER DEFAULT 100,
    is_muted BOOLEAN DEFAULT FALSE,
    repeat_mode VARCHAR(20), -- 'None', 'RepeatAll', 'RepeatOne'
    is_shuffled BOOLEAN DEFAULT FALSE,
    last_update TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_user_device UNIQUE (user_id, device_id),
    CONSTRAINT position_non_negative CHECK (position_ms >= 0),
    CONSTRAINT volume_range CHECK (volume_level >= 0 AND volume_level <= 100)
);

CREATE INDEX idx_sessions_user_id ON sessions(user_id);
CREATE INDEX idx_sessions_device_id ON sessions(device_id);
CREATE INDEX idx_sessions_now_playing ON sessions(now_playing_item_id);
CREATE INDEX idx_sessions_last_update ON sessions(last_update);

-- Library scan history table (for tracking scan operations)
CREATE TABLE library_scans (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    library_root TEXT NOT NULL,
    scan_type VARCHAR(20) NOT NULL, -- 'Full', 'Incremental'
    started_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    files_scanned INTEGER DEFAULT 0,
    files_added INTEGER DEFAULT 0,
    files_updated INTEGER DEFAULT 0,
    files_removed INTEGER DEFAULT 0,
    error_count INTEGER DEFAULT 0,
    status VARCHAR(20) DEFAULT 'Running', -- 'Running', 'Completed', 'Failed'
    error_message TEXT
);

CREATE INDEX idx_library_scans_status ON library_scans(status);
CREATE INDEX idx_library_scans_started_at ON library_scans(started_at DESC);

-- Transcode jobs table (for tracking active transcodes)
CREATE TABLE transcode_jobs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID REFERENCES sessions(id) ON DELETE CASCADE,
    item_id UUID REFERENCES items(id) ON DELETE CASCADE,
    process_id VARCHAR(255),
    codec VARCHAR(50),
    bitrate VARCHAR(20),
    container VARCHAR(50),
    started_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) DEFAULT 'Running' -- 'Running', 'Completed', 'Failed', 'Cancelled'
);

CREATE INDEX idx_transcode_jobs_session_id ON transcode_jobs(session_id);
CREATE INDEX idx_transcode_jobs_item_id ON transcode_jobs(item_id);
CREATE INDEX idx_transcode_jobs_status ON transcode_jobs(status);

-- Functions for automatic timestamp updates
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create triggers for updated_at columns
CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_items_updated_at BEFORE UPDATE ON items
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_playlists_updated_at BEFORE UPDATE ON playlists
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_play_state_updated_at BEFORE UPDATE ON play_state
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Create default admin user (password: admin123 - BCrypt hash with strength 12)
INSERT INTO users (username, password_hash, display_name, is_admin, is_active)
VALUES ('admin', '$2a$12$LQQKDgfqSR2Y5yP6TQjXnOPbFp3DsVqGvV9jZQs5r3Y5QhqROwBLG', 'Administrator', TRUE, TRUE);

-- Add comment documentation
COMMENT ON TABLE users IS 'User accounts for the media server';
COMMENT ON TABLE api_tokens IS 'Authentication tokens bound to user devices';
COMMENT ON TABLE items IS 'Base table for all media items (tracks, albums, artists, folders)';
COMMENT ON TABLE audio_tracks IS 'Audio-specific metadata for music files';
COMMENT ON TABLE albums IS 'Album-specific metadata';
COMMENT ON TABLE artists IS 'Artist-specific metadata';
COMMENT ON TABLE genres IS 'Music genres';
COMMENT ON TABLE item_genres IS 'Many-to-many relationship between items and genres';
COMMENT ON TABLE images IS 'Artwork and images associated with items';
COMMENT ON TABLE playlists IS 'User-created playlists';
COMMENT ON TABLE playlist_entries IS 'Ordered entries in playlists';
COMMENT ON TABLE play_state IS 'Per-user playback state and preferences';
COMMENT ON TABLE sessions IS 'Active playback sessions';
COMMENT ON TABLE library_scans IS 'History of library scan operations';
COMMENT ON TABLE transcode_jobs IS 'Active and historical transcode operations';
