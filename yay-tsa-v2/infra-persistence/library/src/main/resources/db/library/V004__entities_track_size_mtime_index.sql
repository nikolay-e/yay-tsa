-- Rename detection matches vanished rows to moved files by (size_bytes, mtime);
-- without this partial index every cache-miss upsert seq-scans the entities table.
CREATE INDEX idx_entities_track_size_mtime
    ON core_v2_library.entities (size_bytes, mtime)
    WHERE entity_type = 'TRACK';
