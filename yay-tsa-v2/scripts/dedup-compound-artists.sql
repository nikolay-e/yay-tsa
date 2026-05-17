-- Merge compound-artist entities ("SKYND, Bill $Aber", "XACV SQUAD feat Nekto BZ")
-- into their primary artist (substring before the first comma or feat/ft/etc).
-- Matches the Kotlin `primaryArtist()` regex in LibraryWriter.kt.
--
-- Only merges when a primary artist with the same case-insensitive name already
-- exists. Compound rows whose primary has no canonical entity are left alone
-- (rare edge case — would mean the FLAC tags only ever featured the compound).
--
-- Run via: kubectl exec -i -n shared-database shared-postgres-2 -c postgres -- \
--   psql -U postgres -d yaytsa_production < scripts/dedup-compound-artists.sql

BEGIN;

CREATE TEMP TABLE compound_artist_pairs AS
WITH compounds AS (
  SELECT
    e.id AS drop_id,
    e.name AS drop_name,
    regexp_replace(
      e.name,
      '\s*[,;/&]\s+.*$|\s+(feat|ft|featuring|vs|with|x)\.?\s+.*$',
      '',
      'i'
    ) AS primary_name
  FROM core_v2_library.entities e
  JOIN core_v2_library.artists a ON a.entity_id = e.id
  WHERE e.entity_type = 'ARTIST'
    AND e.name ~ '\s*[,;/&]\s+|\s+(feat|ft|featuring|vs)\.?\s+'
)
SELECT
  primary_e.id  AS keep_id,
  c.drop_id     AS drop_id,
  primary_e.name AS keep_name,
  c.drop_name
FROM compounds c
JOIN core_v2_library.entities primary_e
  ON primary_e.entity_type = 'ARTIST'
 AND LOWER(primary_e.name) = LOWER(c.primary_name)
 AND primary_e.id != c.drop_id;

SELECT 'compound_pairs' AS metric, COUNT(*) AS value FROM compound_artist_pairs;
SELECT keep_name, drop_name FROM compound_artist_pairs;

WITH r AS (
  UPDATE core_v2_library.albums a
  SET artist_id = cp.keep_id
  FROM compound_artist_pairs cp
  WHERE a.artist_id = cp.drop_id
  RETURNING a.entity_id
)
SELECT 'albums_remapped' AS metric, COUNT(*) AS value FROM r;

WITH r AS (
  UPDATE core_v2_library.audio_tracks t
  SET album_artist_id = cp.keep_id
  FROM compound_artist_pairs cp
  WHERE t.album_artist_id = cp.drop_id
  RETURNING t.entity_id
)
SELECT 'tracks_remapped' AS metric, COUNT(*) AS value FROM r;

WITH r AS (
  UPDATE core_v2_library.entities e
  SET parent_id = cp.keep_id
  FROM compound_artist_pairs cp
  WHERE e.parent_id = cp.drop_id
  RETURNING e.id
)
SELECT 'entity_parents_remapped' AS metric, COUNT(*) AS value FROM r;

WITH d AS (
  DELETE FROM core_v2_library.entities
  WHERE id IN (SELECT drop_id FROM compound_artist_pairs)
  RETURNING id
)
SELECT 'compound_artists_dropped' AS metric, COUNT(*) AS value FROM d;

-- After dropping compound artists, run album dedup again — some albums may
-- have been the only members of their parent and need to merge with the keep.
CREATE TEMP TABLE post_compound_album_pairs AS
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

SELECT 'post_compound_album_pairs' AS metric, COUNT(*) AS value FROM post_compound_album_pairs;

WITH r AS (
  UPDATE core_v2_library.audio_tracks t
  SET album_id = pp.keep_id
  FROM post_compound_album_pairs pp
  WHERE t.album_id = pp.drop_id
  RETURNING t.entity_id
)
SELECT 'post_compound_tracks_remapped' AS metric, COUNT(*) AS value FROM r;

WITH r AS (
  UPDATE core_v2_library.entities e
  SET parent_id = pp.keep_id
  FROM post_compound_album_pairs pp
  WHERE e.parent_id = pp.drop_id
  RETURNING e.id
)
SELECT 'post_compound_entity_parents_remapped' AS metric, COUNT(*) AS value FROM r;

WITH r AS (
  UPDATE core_v2_playback.play_statistics ps
  SET item_id = pp.keep_id::text
  FROM post_compound_album_pairs pp
  WHERE ps.item_id = pp.drop_id::text
  RETURNING ps.item_id
)
SELECT 'post_compound_play_stats_remapped' AS metric, COUNT(*) AS value FROM r;

WITH d AS (
  DELETE FROM core_v2_library.entities
  WHERE id IN (SELECT drop_id FROM post_compound_album_pairs)
  RETURNING id
)
SELECT 'post_compound_albums_dropped' AS metric, COUNT(*) AS value FROM d;

SELECT 'final_artist_count' AS metric, COUNT(*) AS value
FROM core_v2_library.entities WHERE entity_type = 'ARTIST';

SELECT 'final_album_count' AS metric, COUNT(*) AS value
FROM core_v2_library.entities WHERE entity_type = 'ALBUM';

-- Final sanity
SELECT 'remaining_compound_artists' AS metric, COUNT(*) AS value
FROM core_v2_library.entities
WHERE entity_type = 'ARTIST'
  AND name ~ '\s*[,;/&]\s+|\s+(feat|ft|featuring|vs)\.?\s+';

SELECT 'orphan_track_artist_refs' AS metric, COUNT(*) AS value
FROM core_v2_library.audio_tracks t
LEFT JOIN core_v2_library.entities e ON e.id = t.album_artist_id
WHERE t.album_artist_id IS NOT NULL AND e.id IS NULL;

COMMIT;
