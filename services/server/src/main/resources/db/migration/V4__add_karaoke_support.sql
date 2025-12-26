-- Add karaoke (vocal removal) support to audio tracks
-- Stores paths to separated stems and processing status

ALTER TABLE audio_tracks ADD COLUMN karaoke_ready BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE audio_tracks ADD COLUMN instrumental_path TEXT;
ALTER TABLE audio_tracks ADD COLUMN vocal_path TEXT;

-- Create index for fast lookups of karaoke-ready tracks
CREATE INDEX idx_audio_tracks_karaoke_ready ON audio_tracks (karaoke_ready) WHERE karaoke_ready = TRUE;

COMMENT ON COLUMN audio_tracks.karaoke_ready IS 'Whether instrumental/vocal stems have been generated';
COMMENT ON COLUMN audio_tracks.instrumental_path IS 'Path to instrumental stem (vocals removed)';
COMMENT ON COLUMN audio_tracks.vocal_path IS 'Path to isolated vocal stem';
