ALTER TABLE audio_tracks ADD COLUMN replaygain_track_gain NUMERIC(8, 4);
ALTER TABLE audio_tracks ADD COLUMN replaygain_album_gain NUMERIC(8, 4);
ALTER TABLE audio_tracks ADD COLUMN replaygain_track_peak NUMERIC(8, 6);
