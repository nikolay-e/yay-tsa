-- Fix fingerprint index: btree can't handle long text values (>2704 bytes)
-- Replace with hash index which has no size limit
DROP INDEX IF EXISTS idx_audio_tracks_fingerprint;
CREATE INDEX idx_audio_tracks_fingerprint ON audio_tracks USING hash (fingerprint) WHERE fingerprint IS NOT NULL;
