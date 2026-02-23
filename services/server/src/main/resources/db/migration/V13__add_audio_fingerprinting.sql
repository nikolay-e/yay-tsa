-- Add audio fingerprinting support for duplicate detection
-- Uses Chromaprint/AcoustID fingerprints

-- Add fingerprint fields to audio_tracks table
ALTER TABLE audio_tracks
ADD COLUMN fingerprint TEXT,
ADD COLUMN fingerprint_duration DOUBLE PRECISION,
ADD COLUMN fingerprint_sample_rate INTEGER;

-- Create index for fingerprint lookups (though exact matching won't work on text)
-- We'll do similarity comparison in application code
CREATE INDEX idx_audio_tracks_fingerprint ON audio_tracks(fingerprint) WHERE fingerprint IS NOT NULL;

-- Add comments
COMMENT ON COLUMN audio_tracks.fingerprint IS 'Chromaprint acoustic fingerprint for duplicate detection';
COMMENT ON COLUMN audio_tracks.fingerprint_duration IS 'Audio duration in seconds (from fingerprint)';
COMMENT ON COLUMN audio_tracks.fingerprint_sample_rate IS 'Audio sample rate in Hz';
