-- Dedup artist entities created by v1→v2 ETL with capitalized source_path
-- (e.g. `artist:Кино`) that the v2 scanner later did not match — its `.lowercase()`
-- key (`artist:кино`) hashed differently, so it created a sibling row.
-- See: /qa pass 2026-05-17 — 8 Cyrillic artist dups (Кино, Король и Шут, ...).
--
-- For each `lower(e.name)` group, keep the artist whose source_path is the
-- canonical lowercase form (`artist:<lowercase name>`); if none, keep the
-- oldest. Remap album.artist_id, audio_tracks.album_artist_id, and
-- entities.parent_id (album parents), then DELETE the dropped artist rows.
--
-- Run via: kubectl exec -i -n shared-database shared-postgres-2 -c postgres -- \
--   psql -U postgres -d yaytsa_production < scripts/dedup-artists.sql

BEGIN;

CREATE TEMP TABLE dup_artist_pairs AS
WITH ranked AS (
  SELECT
    e.id,
    e.name,
    e.source_path,
    e.created_at,
    ROW_NUMBER() OVER (
      PARTITION BY LOWER(e.name)
      ORDER BY
        -- Prefer the canonical lowercase-keyed row.
        CASE WHEN e.source_path = 'artist:' || LOWER(e.name) THEN 0 ELSE 1 END,
        e.created_at,
        e.id
    ) AS rn
  FROM core_v2_library.entities e
  JOIN core_v2_library.artists a ON a.entity_id = e.id
  WHERE e.entity_type = 'ARTIST'
)
SELECT
  keep.id  AS keep_id,
  drop_.id AS drop_id,
  keep.name
FROM ranked keep
JOIN ranked drop_ ON LOWER(drop_.name) = LOWER(keep.name)
WHERE keep.rn = 1 AND drop_.rn > 1;

SELECT 'artist_pairs_to_dedup' AS metric, COUNT(*) AS value FROM dup_artist_pairs;

WITH r AS (
  UPDATE core_v2_library.albums a
  SET artist_id = dp.keep_id
  FROM dup_artist_pairs dp
  WHERE a.artist_id = dp.drop_id
  RETURNING a.entity_id
)
SELECT 'albums_artist_id_remapped' AS metric, COUNT(*) AS value FROM r;

WITH r AS (
  UPDATE core_v2_library.audio_tracks t
  SET album_artist_id = dp.keep_id
  FROM dup_artist_pairs dp
  WHERE t.album_artist_id = dp.drop_id
  RETURNING t.entity_id
)
SELECT 'tracks_album_artist_id_remapped' AS metric, COUNT(*) AS value FROM r;

WITH r AS (
  UPDATE core_v2_library.entities e
  SET parent_id = dp.keep_id
  FROM dup_artist_pairs dp
  WHERE e.parent_id = dp.drop_id
  RETURNING e.id
)
SELECT 'entity_parents_remapped' AS metric, COUNT(*) AS value FROM r;

WITH d AS (
  DELETE FROM core_v2_library.entities
  WHERE id IN (SELECT drop_id FROM dup_artist_pairs)
  RETURNING id
)
SELECT 'artists_dropped' AS metric, COUNT(*) AS value FROM d;

-- Sanity: case-insensitive artist names must collapse to exactly 1 entity.
SELECT 'remaining_artist_dup_groups' AS metric, COUNT(*) AS value
FROM (
  SELECT 1 FROM core_v2_library.entities
  WHERE entity_type = 'ARTIST'
  GROUP BY LOWER(name) HAVING COUNT(*) > 1
) g;

SELECT 'final_artist_count' AS metric, COUNT(*) AS value
FROM core_v2_library.entities WHERE entity_type = 'ARTIST';

-- After artist dedup, run album dedup AGAIN — albums that previously
-- partitioned by different artist_ids may now collapse.
CREATE TEMP TABLE dup_album_pairs2 AS
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
SELECT keep.id AS keep_id, drop_.id AS drop_id
FROM ranked keep
JOIN ranked drop_ USING (name, artist_id)
WHERE keep.rn = 1 AND drop_.rn > 1;

SELECT 'second_pass_album_pairs' AS metric, COUNT(*) AS value FROM dup_album_pairs2;

WITH r AS (
  UPDATE core_v2_library.audio_tracks t
  SET album_id = dp.keep_id
  FROM dup_album_pairs2 dp
  WHERE t.album_id = dp.drop_id
  RETURNING t.entity_id
)
SELECT 'second_pass_tracks_remapped' AS metric, COUNT(*) AS value FROM r;

WITH r AS (
  UPDATE core_v2_library.entities e
  SET parent_id = dp.keep_id
  FROM dup_album_pairs2 dp
  WHERE e.parent_id = dp.drop_id
  RETURNING e.id
)
SELECT 'second_pass_entity_parents_remapped' AS metric, COUNT(*) AS value FROM r;

WITH r AS (
  UPDATE core_v2_playback.play_statistics ps
  SET item_id = dp.keep_id::text
  FROM dup_album_pairs2 dp
  WHERE ps.item_id = dp.drop_id::text
  RETURNING ps.item_id
)
SELECT 'second_pass_play_stats_remapped' AS metric, COUNT(*) AS value FROM r;

WITH d AS (
  DELETE FROM core_v2_library.entities
  WHERE id IN (SELECT drop_id FROM dup_album_pairs2)
  RETURNING id
)
SELECT 'second_pass_albums_dropped' AS metric, COUNT(*) AS value FROM d;

SELECT 'final_album_count' AS metric, COUNT(*) AS value
FROM core_v2_library.entities WHERE entity_type = 'ALBUM';

-- Final sanity: track-album orphans, no remaining dup groups.
SELECT 'orphan_track_album_refs' AS metric, COUNT(*) AS value
FROM core_v2_library.audio_tracks t
LEFT JOIN core_v2_library.entities e ON e.id = t.album_id
WHERE t.album_id IS NOT NULL AND e.id IS NULL;

SELECT 'remaining_dup_album_groups' AS metric, COUNT(*) AS value
FROM (
  SELECT 1 FROM core_v2_library.entities e
  JOIN core_v2_library.albums a ON a.entity_id = e.id
  WHERE e.entity_type = 'ALBUM'
  GROUP BY e.name, a.artist_id
  HAVING COUNT(*) > 1
) g;

COMMIT;
