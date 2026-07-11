-- Genres were stored as raw file-tag strings: multi-value tags kept whole ("Symphonic metal, heavy
-- metal, power metal") and case variants split apart ("Power Metal" vs "Power metal"). So a track
-- tagged "power metal" and one tagged inside a combo did NOT share the atomic "power metal", making
-- genre aggregation meaningless. Genres are overlapping/nested SETS, not one-per-track buckets.
--
-- Part 1: split every genre membership into atomic, initcap-normalized genres and re-link. Matches
-- the scanner's normalization (split on [,;|/], collapse whitespace, initcap) so both converge.
DO $$
DECLARE
    rec  RECORD;
    part TEXT;
    atomic TEXT;
    gid  UUID;
BEGIN
    FOR rec IN
        SELECT eg.entity_id, g.name
        FROM core_v2_library.entity_genres eg
        JOIN core_v2_library.genres g ON g.id = eg.genre_id
    LOOP
        FOR part IN SELECT regexp_split_to_table(rec.name, '[,;|/]') LOOP
            atomic := initcap(btrim(regexp_replace(part, '\s+', ' ', 'g')));
            CONTINUE WHEN atomic = '';
            SELECT id INTO gid FROM core_v2_library.genres WHERE name = atomic;
            IF gid IS NULL THEN
                gid := gen_random_uuid();
                INSERT INTO core_v2_library.genres (id, name) VALUES (gid, atomic) ON CONFLICT (name) DO NOTHING;
                SELECT id INTO gid FROM core_v2_library.genres WHERE name = atomic;
            END IF;
            INSERT INTO core_v2_library.entity_genres (entity_id, genre_id)
            VALUES (rec.entity_id, gid) ON CONFLICT DO NOTHING;
        END LOOP;
    END LOOP;

    -- Drop the redundant source rows: multi-value strings and case/whitespace variants that are not
    -- already their own atomic normalized form. Their entity_genres cascade; atomic links added above.
    DELETE FROM core_v2_library.genres g
    WHERE g.name ~ '[,;|/]'
       OR g.name <> initcap(btrim(regexp_replace(g.name, '\s+', ' ', 'g')));
END $$;

-- Part 2: represent the subset/nesting relationships between atomic genres. A genre is a subgenre of
-- any genre that is a whitespace-delimited suffix of its name ("Melodic Death Metal" -> "Death Metal"
-- and -> "Metal"). Derived purely from names (no hardcoded taxonomy); stores the full ancestor set so
-- consumers can roll a subgenre up to any of its parents.
CREATE TABLE core_v2_library.genre_relations (
    child_id  UUID NOT NULL REFERENCES core_v2_library.genres(id) ON DELETE CASCADE,
    parent_id UUID NOT NULL REFERENCES core_v2_library.genres(id) ON DELETE CASCADE,
    PRIMARY KEY (child_id, parent_id)
);

CREATE INDEX idx_genre_relations_parent ON core_v2_library.genre_relations (parent_id);

INSERT INTO core_v2_library.genre_relations (child_id, parent_id)
SELECT c.id, p.id
FROM core_v2_library.genres c
JOIN core_v2_library.genres p
  ON length(c.name) > length(p.name) + 1
 AND right(lower(c.name), length(p.name) + 1) = ' ' || lower(p.name)
ON CONFLICT DO NOTHING;
