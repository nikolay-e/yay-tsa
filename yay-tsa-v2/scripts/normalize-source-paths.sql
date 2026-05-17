-- Normalize `core_v2_library.entities.source_path` for ARTIST and ALBUM rows
-- to the canonical lowercase form. Default Postgres C-locale `lower()` does
-- NOT case-fold Cyrillic — must use ICU collation `und-x-icu`.
--
-- Without this normalization, the v2 scanner's lookup
-- `findBySourcePath("artist:" + name.lowercase())` misses the legacy ETL-keyed
-- row `artist:Кино` and inserts a fresh `artist:кино` sibling. Cycle repeats
-- on every scan, producing 2x Cyrillic dup pairs.
--
-- Run via: kubectl exec -i ... < scripts/normalize-source-paths.sql

BEGIN;

-- Find collisions BEFORE the UPDATE: if two rows would normalize to the same
-- source_path, keep the older and remap references to the younger.
CREATE TEMP TABLE source_path_collisions AS
WITH normalized AS (
  SELECT
    e.id,
    e.entity_type,
    e.name,
    e.source_path AS old_path,
    CASE
      WHEN e.entity_type IN ('ARTIST', 'ALBUM') AND e.source_path ~ '^(artist|album):'
        THEN lower(e.source_path COLLATE "und-x-icu")
      ELSE e.source_path
    END AS new_path,
    e.created_at
  FROM core_v2_library.entities e
  WHERE e.entity_type IN ('ARTIST', 'ALBUM')
),
ranked AS (
  SELECT *,
    ROW_NUMBER() OVER (PARTITION BY entity_type, new_path ORDER BY created_at, id) AS rn,
    COUNT(*) OVER (PARTITION BY entity_type, new_path) AS group_size
  FROM normalized
)
SELECT
  keep.id AS keep_id,
  drop_.id AS drop_id,
  keep.entity_type AS entity_type,
  keep.new_path AS canonical_path
FROM ranked keep
JOIN ranked drop_ USING (entity_type, new_path)
WHERE keep.rn = 1 AND drop_.rn > 1;

SELECT 'collision_pairs' AS metric, COUNT(*) AS value FROM source_path_collisions;

-- 1. Remap cross-entity references from drop → keep BEFORE UPDATE/DELETE.
-- 1a. audio_tracks.album_id (ALBUM collisions)
WITH r AS (
  UPDATE core_v2_library.audio_tracks t
  SET album_id = c.keep_id
  FROM source_path_collisions c
  WHERE c.entity_type = 'ALBUM' AND t.album_id = c.drop_id
  RETURNING t.entity_id
)
SELECT 'tracks_album_id_remapped' AS metric, COUNT(*) AS value FROM r;

-- 1b. audio_tracks.album_artist_id (ARTIST collisions)
WITH r AS (
  UPDATE core_v2_library.audio_tracks t
  SET album_artist_id = c.keep_id
  FROM source_path_collisions c
  WHERE c.entity_type = 'ARTIST' AND t.album_artist_id = c.drop_id
  RETURNING t.entity_id
)
SELECT 'tracks_album_artist_id_remapped' AS metric, COUNT(*) AS value FROM r;

-- 1c. albums.artist_id (ARTIST collisions)
WITH r AS (
  UPDATE core_v2_library.albums a
  SET artist_id = c.keep_id
  FROM source_path_collisions c
  WHERE c.entity_type = 'ARTIST' AND a.artist_id = c.drop_id
  RETURNING a.entity_id
)
SELECT 'albums_artist_id_remapped' AS metric, COUNT(*) AS value FROM r;

-- 1d. entities.parent_id (child of dropped entity)
WITH r AS (
  UPDATE core_v2_library.entities e
  SET parent_id = c.keep_id
  FROM source_path_collisions c
  WHERE e.parent_id = c.drop_id
  RETURNING e.id
)
SELECT 'entities_parent_id_remapped' AS metric, COUNT(*) AS value FROM r;

-- 1e. play_statistics.item_id (ALBUM by-value refs across schema)
WITH r AS (
  UPDATE core_v2_playback.play_statistics ps
  SET item_id = c.keep_id::text
  FROM source_path_collisions c
  WHERE c.entity_type = 'ALBUM' AND ps.item_id = c.drop_id::text
  RETURNING ps.item_id
)
SELECT 'play_statistics_item_id_remapped' AS metric, COUNT(*) AS value FROM r;

-- 2. Drop the duplicate entity rows.
WITH d AS (
  DELETE FROM core_v2_library.entities
  WHERE id IN (SELECT drop_id FROM source_path_collisions)
  RETURNING id
)
SELECT 'entities_dropped' AS metric, COUNT(*) AS value FROM d;

-- 3. Now update remaining ARTIST/ALBUM source_paths to canonical lowercase.
WITH r AS (
  UPDATE core_v2_library.entities e
  SET source_path = lower(e.source_path COLLATE "und-x-icu")
  WHERE entity_type IN ('ARTIST', 'ALBUM')
    AND source_path ~ '^(artist|album):'
    AND source_path != lower(e.source_path COLLATE "und-x-icu")
  RETURNING e.id
)
SELECT 'source_path_normalized' AS metric, COUNT(*) AS value FROM r;

-- Sanity checks
SELECT 'remaining_dup_artist_groups' AS metric, COUNT(*) AS value
FROM (
  SELECT 1 FROM core_v2_library.entities
  WHERE entity_type = 'ARTIST'
  GROUP BY lower(name COLLATE "und-x-icu")
  HAVING COUNT(*) > 1
) g;

SELECT 'remaining_non_canonical_paths' AS metric, COUNT(*) AS value
FROM core_v2_library.entities
WHERE entity_type IN ('ARTIST', 'ALBUM')
  AND source_path ~ '^(artist|album):'
  AND source_path != lower(source_path COLLATE "und-x-icu");

SELECT 'final_artist_count' AS metric, COUNT(*) AS value
FROM core_v2_library.entities WHERE entity_type = 'ARTIST';

SELECT 'final_album_count' AS metric, COUNT(*) AS value
FROM core_v2_library.entities WHERE entity_type = 'ALBUM';

SELECT 'orphan_track_album_refs' AS metric, COUNT(*) AS value
FROM core_v2_library.audio_tracks t
LEFT JOIN core_v2_library.entities e ON e.id = t.album_id
WHERE t.album_id IS NOT NULL AND e.id IS NULL;

COMMIT;
