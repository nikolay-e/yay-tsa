-- Orphan cleanup: when last track in album is deleted, delete the empty album.
-- When last album in artist is deleted, delete the empty artist.
-- When last entity referencing a genre is removed, delete the genre.

CREATE OR REPLACE FUNCTION core_v2_library.cleanup_empty_parent()
RETURNS TRIGGER AS $$
DECLARE
    parent UUID;
    grandparent UUID;
    child_count INTEGER;
BEGIN
    parent := OLD.parent_id;
    IF parent IS NULL THEN
        RETURN OLD;
    END IF;

    SELECT count(*) INTO child_count
    FROM core_v2_library.entities
    WHERE parent_id = parent;

    IF child_count = 0 THEN
        SELECT parent_id INTO grandparent
        FROM core_v2_library.entities WHERE id = parent;

        DELETE FROM core_v2_library.entities WHERE id = parent;

        -- Recurse: if grandparent now empty, clean it too
        IF grandparent IS NOT NULL THEN
            SELECT count(*) INTO child_count
            FROM core_v2_library.entities
            WHERE parent_id = grandparent;

            IF child_count = 0 THEN
                DELETE FROM core_v2_library.entities WHERE id = grandparent;
            END IF;
        END IF;
    END IF;

    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_cleanup_empty_parent
    AFTER DELETE ON core_v2_library.entities
    FOR EACH ROW
    WHEN (OLD.parent_id IS NOT NULL)
    EXECUTE FUNCTION core_v2_library.cleanup_empty_parent();

-- Orphan genre cleanup
CREATE OR REPLACE FUNCTION core_v2_library.cleanup_orphan_genres()
RETURNS TRIGGER AS $$
BEGIN
    DELETE FROM core_v2_library.genres g
    WHERE NOT EXISTS (
        SELECT 1 FROM core_v2_library.entity_genres eg WHERE eg.genre_id = g.id
    );
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_cleanup_orphan_genres
    AFTER DELETE ON core_v2_library.entity_genres
    FOR EACH ROW
    EXECUTE FUNCTION core_v2_library.cleanup_orphan_genres();
