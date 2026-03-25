ALTER TABLE listening_session ADD COLUMN seed_track_id UUID REFERENCES items(id) ON DELETE SET NULL;
