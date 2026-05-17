-- Dedup album entities created by the pre-b453b32e scanner that used
-- featured-artist lists (not the album-artist) in the source_path key.
-- See: /qa pass 2026-05-17 — Horus -> 30 dups, Hatters -> 12, etc.
-- Total: 803 album entities for 593 distinct (name, artist_id) pairs.
--
-- For each (name, artist_id) group: keep the OLDEST entity (by created_at),
-- remap audio_tracks.album_id + entities.parent_id + play_statistics.item_id
-- to point at the keep, then DELETE the rest. CASCADE clears
-- core_v2_library.albums, .images, .entity_genres for the dropped rows.
--
-- Run via: kubectl exec -i -n shared-database shared-postgres-2 -c postgres -- \
--   psql -U postgres -d yaytsa_production < scripts/dedup-albums.sql

BEGIN;

CREATE TEMP TABLE dup_album_pairs AS
WITH ranked AS (
  SELECT
    e.id,
    e.name,
    a.artist_id,
    e.created_at,
    ROW_NUMBER() OVER (
      PARTITION BY e.name, COALESCE(a.artist_id::text, '')
      ORDER BY e.created_at, e.id
    ) AS rn
  FROM core_v2_library.entities e
  JOIN core_v2_library.albums a ON a.entity_id = e.id
  WHERE e.entity_type = 'ALBUM'
)
SELECT
  keep.id   AS keep_id,
  drop_.id  AS drop_id,
  keep.name AS name
FROM ranked keep
JOIN ranked drop_ USING (name, artist_id)
WHERE keep.rn = 1
  AND drop_.rn > 1;

SELECT 'pairs_to_dedup' AS metric, COUNT(*) AS value FROM dup_album_pairs;

WITH remapped_tracks AS (
  UPDATE core_v2_library.audio_tracks t
  SET album_id = dp.keep_id
  FROM dup_album_pairs dp
  WHERE t.album_id = dp.drop_id
  RETURNING t.entity_id
)
SELECT 'tracks_album_id_remapped' AS metric, COUNT(*) AS value FROM remapped_tracks;

WITH remapped_parents AS (
  UPDATE core_v2_library.entities e
  SET parent_id = dp.keep_id
  FROM dup_album_pairs dp
  WHERE e.parent_id = dp.drop_id
  RETURNING e.id
)
SELECT 'entities_parent_id_remapped' AS metric, COUNT(*) AS value FROM remapped_parents;

WITH remapped_stats AS (
  UPDATE core_v2_playback.play_statistics ps
  SET item_id = dp.keep_id::text
  FROM dup_album_pairs dp
  WHERE ps.item_id = dp.drop_id::text
  RETURNING ps.item_id
)
SELECT 'play_statistics_item_id_remapped' AS metric, COUNT(*) AS value FROM remapped_stats;

WITH dropped AS (
  DELETE FROM core_v2_library.entities
  WHERE id IN (SELECT drop_id FROM dup_album_pairs)
  RETURNING id
)
SELECT 'entities_dropped' AS metric, COUNT(*) AS value FROM dropped;

-- Sanity: after dedup, every (name, artist_id) group must collapse to exactly 1 row.
SELECT 'remaining_dup_groups' AS metric, COUNT(*) AS value
FROM (
  SELECT 1 FROM core_v2_library.entities e
  JOIN core_v2_library.albums a ON a.entity_id = e.id
  WHERE e.entity_type = 'ALBUM'
  GROUP BY e.name, a.artist_id
  HAVING COUNT(*) > 1
) g;

SELECT 'final_album_count' AS metric, COUNT(*) AS value
FROM core_v2_library.entities WHERE entity_type = 'ALBUM';

-- Track-orphan check: no audio_tracks should still point at a dropped album.
SELECT 'orphan_track_album_refs' AS metric, COUNT(*) AS value
FROM core_v2_library.audio_tracks t
LEFT JOIN core_v2_library.entities e ON e.id = t.album_id
WHERE t.album_id IS NOT NULL AND e.id IS NULL;

COMMIT;
