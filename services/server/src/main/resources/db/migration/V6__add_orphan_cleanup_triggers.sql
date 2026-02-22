-- Automatic orphan cleanup: cascade-up deletion for empty parents and unused genres
--
-- items hierarchy: MusicArtist → MusicAlbum → AudioTrack (via parent_id)
-- Problem: ON DELETE CASCADE only works downward (delete artist → albums → tracks).
-- When the last track is removed from an album, the empty album stays. Same for artists.
-- These triggers solve the reverse direction.

-- 1. Delete empty parent items when their last child is removed.
--    Works recursively: deleting last track → empty album deleted → empty artist deleted.
CREATE OR REPLACE FUNCTION cleanup_empty_parent()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.parent_id IS NOT NULL THEN
        PERFORM 1 FROM items WHERE id = OLD.parent_id FOR UPDATE;
        IF NOT EXISTS (SELECT 1 FROM items WHERE parent_id = OLD.parent_id) THEN
            DELETE FROM items WHERE id = OLD.parent_id;
        END IF;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_cleanup_empty_parent
    AFTER DELETE ON items
    FOR EACH ROW
    EXECUTE FUNCTION cleanup_empty_parent();

-- 2. Delete genres that no longer have any associated items.
CREATE OR REPLACE FUNCTION cleanup_orphan_genre()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM item_genres WHERE genre_id = OLD.genre_id) THEN
        DELETE FROM genres WHERE id = OLD.genre_id;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_cleanup_orphan_genre
    AFTER DELETE ON item_genres
    FOR EACH ROW
    EXECUTE FUNCTION cleanup_orphan_genre();
