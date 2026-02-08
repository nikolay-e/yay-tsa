-- Clean up orphaned parent items when a child's parent_id is changed via UPDATE.
-- Complements V6's DELETE trigger: when a track moves to a different album during
-- library rescan, the old album (and its artist) are cleaned up if they become empty.

CREATE OR REPLACE FUNCTION cleanup_orphaned_parent_on_update()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.parent_id IS DISTINCT FROM NEW.parent_id AND OLD.parent_id IS NOT NULL THEN
        PERFORM 1 FROM items WHERE id = OLD.parent_id FOR UPDATE;
        IF NOT EXISTS (SELECT 1 FROM items WHERE parent_id = OLD.parent_id) THEN
            DELETE FROM items WHERE id = OLD.parent_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_cleanup_orphaned_parent_on_update
    AFTER UPDATE OF parent_id ON items
    FOR EACH ROW
    EXECUTE FUNCTION cleanup_orphaned_parent_on_update();
