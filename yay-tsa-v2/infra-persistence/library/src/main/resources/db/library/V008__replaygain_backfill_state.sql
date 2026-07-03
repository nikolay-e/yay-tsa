ALTER TABLE core_v2_library.audio_tracks ADD COLUMN replaygain_checked_at TIMESTAMPTZ;
CREATE INDEX idx_audio_tracks_replaygain_unchecked ON core_v2_library.audio_tracks (entity_id)
    WHERE replaygain_track_gain IS NULL
      AND replaygain_album_gain IS NULL
      AND replaygain_track_peak IS NULL
      AND replaygain_checked_at IS NULL;
