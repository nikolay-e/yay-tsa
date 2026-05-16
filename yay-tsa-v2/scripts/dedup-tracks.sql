-- Dedup audio tracks left over from v1 ETL (absolute paths) overlapping with
-- v2 library scanner output (relative paths). Same physical file ingested twice.
--
-- Strategy:
--   * Build dup_pairs(keep_id, drop_id) from groups of
--     (album_id, track_number, disc_number) with >1 audio_tracks row.
--   * Prefer the row whose entities.source_path is RELATIVE (does NOT start with '/')
--     because the v2 scanner is canonical going forward and its relative paths
--     correctly resolve under the configured library_root.
--   * Remap all cross-schema references from drop_id -> keep_id.
--   * DELETE the drop entities. CASCADE handles audio_tracks/images/entity_genres.
--
-- Cross-schema reference inventory (column types are mixed):
--   core_v2_preferences.favorites           (track_id   varchar(255), PK (user_id, track_id))
--   core_v2_playback.play_history           (item_id    varchar(255), no FK constraint)
--   core_v2_playback.play_statistics        (item_id    varchar(36),  PK (user_id, item_id))
--   core_v2_playback.queue_entries          (currently empty)
--   core_v2_playlists.playlist_tracks       (currently empty)
--   core_v2_ml.track_features               (track_id   uuid,         PK track_id)
--   core_v2_ml.user_track_affinity          (track_id   uuid,         PK (user_id, track_id))
--   core_v2_karaoke.assets                  (track_id   uuid,         PK track_id)

BEGIN;

SET LOCAL statement_timeout = '900s';
SET LOCAL lock_timeout = '60s';

-- 0. Baseline metrics so we can verify before COMMIT.
\echo '--- baseline ---'
SELECT 'entities_track_total' AS metric, count(*) AS value
FROM core_v2_library.entities WHERE entity_type='TRACK'
UNION ALL SELECT 'audio_tracks_total', count(*) FROM core_v2_library.audio_tracks
UNION ALL SELECT 'favorites_total', count(*) FROM core_v2_preferences.favorites
UNION ALL SELECT 'play_history_total', count(*) FROM core_v2_playback.play_history
UNION ALL SELECT 'play_statistics_total', count(*) FROM core_v2_playback.play_statistics
UNION ALL SELECT 'track_features_total', count(*) FROM core_v2_ml.track_features
UNION ALL SELECT 'user_track_affinity_total', count(*) FROM core_v2_ml.user_track_affinity
UNION ALL SELECT 'karaoke_assets_total', count(*) FROM core_v2_karaoke.assets;

-- 1. Build dup_pairs. For each (album_id, track_number, disc_number) group with >1 row,
--    pick KEEP = relative-path row if it exists, otherwise the lexicographically
--    smallest entity_id for determinism. Every other row in the group becomes a DROP.
CREATE TEMP TABLE dup_pairs (
    keep_id uuid NOT NULL,
    drop_id uuid NOT NULL PRIMARY KEY
) ON COMMIT DROP;

WITH groups AS (
    SELECT
        t.entity_id,
        e.source_path,
        t.album_id,
        t.track_number,
        COALESCE(t.disc_number, 1) AS disc_number,
        (e.source_path NOT LIKE '/%') AS is_relative
    FROM core_v2_library.audio_tracks t
    JOIN core_v2_library.entities e ON e.id = t.entity_id
    WHERE t.album_id IS NOT NULL AND t.track_number IS NOT NULL
),
ranked AS (
    SELECT
        entity_id,
        album_id,
        track_number,
        disc_number,
        ROW_NUMBER() OVER (
            PARTITION BY album_id, track_number, disc_number
            ORDER BY is_relative DESC, entity_id
        ) AS rn,
        FIRST_VALUE(entity_id) OVER (
            PARTITION BY album_id, track_number, disc_number
            ORDER BY is_relative DESC, entity_id
        ) AS keep_id
    FROM groups
)
INSERT INTO dup_pairs (keep_id, drop_id)
SELECT keep_id, entity_id FROM ranked WHERE rn > 1;

\echo '--- dup_pairs ---'
SELECT count(*) AS planned_drops FROM dup_pairs;
SELECT count(DISTINCT keep_id) AS distinct_keeps FROM dup_pairs;
SELECT (SELECT count(*) FROM dup_pairs dp WHERE NOT EXISTS (SELECT 1 FROM core_v2_library.audio_tracks t WHERE t.entity_id = dp.keep_id)) AS keeps_missing_audio_track;

-- 2. Remap cross-schema references. UUID and varchar columns need different casts.
--    For PK-constrained tables we do WHERE NOT EXISTS to avoid unique violations,
--    then DELETE the rows that collided (the drop side loses to existing keep entries).

-- 2a. favorites (varchar) - PK (user_id, track_id).
\echo '--- favorites ---'
WITH up AS (
    UPDATE core_v2_preferences.favorites f
    SET track_id = dp.keep_id::text
    FROM dup_pairs dp
    WHERE f.track_id = dp.drop_id::text
      AND NOT EXISTS (
          SELECT 1 FROM core_v2_preferences.favorites f2
          WHERE f2.user_id = f.user_id AND f2.track_id = dp.keep_id::text
      )
    RETURNING 1
) SELECT count(*) AS favorites_remapped FROM up;
WITH del AS (
    DELETE FROM core_v2_preferences.favorites f
    USING dup_pairs dp
    WHERE f.track_id = dp.drop_id::text
    RETURNING 1
) SELECT count(*) AS favorites_collisions_deleted FROM del;

-- 2b. play_history (varchar) - no unique constraint, plain UPDATE.
\echo '--- play_history ---'
WITH up AS (
    UPDATE core_v2_playback.play_history h
    SET item_id = dp.keep_id::text
    FROM dup_pairs dp
    WHERE h.item_id = dp.drop_id::text
    RETURNING 1
) SELECT count(*) AS play_history_remapped FROM up;

-- 2c. play_statistics (varchar) - PK (user_id, item_id). Merge counters when both exist.
\echo '--- play_statistics ---'
WITH merged AS (
    UPDATE core_v2_playback.play_statistics keep
    SET play_count = keep.play_count + drop_row.play_count,
        last_played_at = GREATEST(keep.last_played_at, drop_row.last_played_at),
        playback_position_ms = GREATEST(keep.playback_position_ms, drop_row.playback_position_ms),
        is_favorite = keep.is_favorite OR drop_row.is_favorite,
        updated_at = now()
    FROM core_v2_playback.play_statistics drop_row
    JOIN dup_pairs dp ON dp.drop_id::text = drop_row.item_id
    WHERE keep.user_id = drop_row.user_id
      AND keep.item_id = dp.keep_id::text
    RETURNING 1
) SELECT count(*) AS play_statistics_merged FROM merged;
WITH up AS (
    UPDATE core_v2_playback.play_statistics s
    SET item_id = dp.keep_id::text
    FROM dup_pairs dp
    WHERE s.item_id = dp.drop_id::text
      AND NOT EXISTS (
          SELECT 1 FROM core_v2_playback.play_statistics s2
          WHERE s2.user_id = s.user_id AND s2.item_id = dp.keep_id::text
      )
    RETURNING 1
) SELECT count(*) AS play_statistics_remapped FROM up;
WITH del AS (
    DELETE FROM core_v2_playback.play_statistics s
    USING dup_pairs dp
    WHERE s.item_id = dp.drop_id::text
    RETURNING 1
) SELECT count(*) AS play_statistics_collisions_deleted FROM del;

-- 2d. track_features (uuid) - PK track_id. INSERT one row per keep_id from the
--      newest drop's features when keep has none; then DELETE all drop rows.
\echo '--- track_features ---'
WITH transfer AS (
    INSERT INTO core_v2_ml.track_features
        (track_id, bpm, bpm_confidence, musical_key, key_confidence, energy,
         loudness_integrated, loudness_range, average_loudness, valence, arousal,
         danceability, vocal_instrumental, voice_gender, spectral_complexity,
         dissonance, onset_rate, intro_duration_sec,
         embedding_discogs, embedding_musicnn, embedding_clap, embedding_mert,
         extracted_at, extractor_version)
    SELECT DISTINCT ON (dp.keep_id)
        dp.keep_id, f.bpm, f.bpm_confidence, f.musical_key, f.key_confidence, f.energy,
        f.loudness_integrated, f.loudness_range, f.average_loudness, f.valence, f.arousal,
        f.danceability, f.vocal_instrumental, f.voice_gender, f.spectral_complexity,
        f.dissonance, f.onset_rate, f.intro_duration_sec,
        f.embedding_discogs, f.embedding_musicnn, f.embedding_clap, f.embedding_mert,
        f.extracted_at, f.extractor_version
    FROM core_v2_ml.track_features f
    JOIN dup_pairs dp ON dp.drop_id = f.track_id
    WHERE NOT EXISTS (
        SELECT 1 FROM core_v2_ml.track_features f2 WHERE f2.track_id = dp.keep_id
    )
    ORDER BY dp.keep_id, f.extracted_at DESC
    ON CONFLICT (track_id) DO NOTHING
    RETURNING 1
) SELECT count(*) AS track_features_transferred FROM transfer;
WITH del AS (
    DELETE FROM core_v2_ml.track_features f
    USING dup_pairs dp
    WHERE f.track_id = dp.drop_id
    RETURNING 1
) SELECT count(*) AS track_features_drop_rows_deleted FROM del;

-- 2e. user_track_affinity (uuid) - PK (user_id, track_id).
--      Aggregate all drop rows per (user_id, keep_id) into a single upsert payload,
--      merge into keep row when it exists else insert.
\echo '--- user_track_affinity ---'
WITH aggregated AS (
    SELECT
        a.user_id,
        dp.keep_id AS track_id,
        SUM(a.play_count) AS play_count,
        SUM(a.completion_count) AS completion_count,
        SUM(a.skip_count) AS skip_count,
        SUM(a.thumbs_up_count) AS thumbs_up_count,
        SUM(a.thumbs_down_count) AS thumbs_down_count,
        SUM(a.total_listen_sec) AS total_listen_sec,
        MAX(a.affinity_score) AS affinity_score,
        MAX(a.last_signal_at) AS last_signal_at
    FROM core_v2_ml.user_track_affinity a
    JOIN dup_pairs dp ON dp.drop_id = a.track_id
    GROUP BY a.user_id, dp.keep_id
),
upserted AS (
    INSERT INTO core_v2_ml.user_track_affinity AS tgt
        (user_id, track_id, affinity_score, play_count, completion_count, skip_count,
         thumbs_up_count, thumbs_down_count, total_listen_sec, last_signal_at, updated_at)
    SELECT
        user_id, track_id, affinity_score, play_count, completion_count, skip_count,
        thumbs_up_count, thumbs_down_count, total_listen_sec, last_signal_at, now()
    FROM aggregated
    ON CONFLICT (user_id, track_id) DO UPDATE SET
        play_count = tgt.play_count + EXCLUDED.play_count,
        completion_count = tgt.completion_count + EXCLUDED.completion_count,
        skip_count = tgt.skip_count + EXCLUDED.skip_count,
        thumbs_up_count = tgt.thumbs_up_count + EXCLUDED.thumbs_up_count,
        thumbs_down_count = tgt.thumbs_down_count + EXCLUDED.thumbs_down_count,
        total_listen_sec = tgt.total_listen_sec + EXCLUDED.total_listen_sec,
        affinity_score = GREATEST(tgt.affinity_score, EXCLUDED.affinity_score),
        last_signal_at = GREATEST(tgt.last_signal_at, EXCLUDED.last_signal_at),
        updated_at = now()
    RETURNING 1
) SELECT count(*) AS user_track_affinity_upserted FROM upserted;
WITH del AS (
    DELETE FROM core_v2_ml.user_track_affinity a
    USING dup_pairs dp
    WHERE a.track_id = dp.drop_id
    RETURNING 1
) SELECT count(*) AS user_track_affinity_drop_rows_deleted FROM del;

-- 2f. karaoke.assets (uuid) - PK track_id. Transfer at most one drop's assets per keep_id.
\echo '--- karaoke.assets ---'
WITH transfer AS (
    INSERT INTO core_v2_karaoke.assets
        (track_id, instrumental_path, vocal_path, lyrics_timing, ready_at)
    SELECT DISTINCT ON (dp.keep_id)
        dp.keep_id, a.instrumental_path, a.vocal_path, a.lyrics_timing, a.ready_at
    FROM core_v2_karaoke.assets a
    JOIN dup_pairs dp ON dp.drop_id = a.track_id
    WHERE NOT EXISTS (
        SELECT 1 FROM core_v2_karaoke.assets a2 WHERE a2.track_id = dp.keep_id
    )
    ORDER BY dp.keep_id, a.ready_at DESC NULLS LAST
    ON CONFLICT (track_id) DO NOTHING
    RETURNING 1
) SELECT count(*) AS karaoke_assets_transferred FROM transfer;
WITH del AS (
    DELETE FROM core_v2_karaoke.assets a
    USING dup_pairs dp
    WHERE a.track_id = dp.drop_id
    RETURNING 1
) SELECT count(*) AS karaoke_assets_drop_rows_deleted FROM del;

-- 3. Delete the drop entities. CASCADE removes audio_tracks/images/entity_genres rows
--    keyed off entities.id. orphan_cleanup triggers may also fire on parent_id paths.
\echo '--- delete drop entities ---'
WITH del AS (
    DELETE FROM core_v2_library.entities e
    USING dup_pairs dp
    WHERE e.id = dp.drop_id
    RETURNING 1
) SELECT count(*) AS entities_dropped FROM del;

-- 4. Sanity: no remaining duplicates under the same (album_id, track_number, disc_number).
\echo '--- post-dedup verification ---'
SELECT count(*) AS remaining_dup_groups FROM (
    SELECT 1 FROM core_v2_library.audio_tracks
    WHERE album_id IS NOT NULL AND track_number IS NOT NULL
    GROUP BY album_id, track_number, COALESCE(disc_number, 1)
    HAVING COUNT(*) > 1
) s;

SELECT 'entities_track_total' AS metric, count(*) AS value
FROM core_v2_library.entities WHERE entity_type='TRACK'
UNION ALL SELECT 'audio_tracks_total', count(*) FROM core_v2_library.audio_tracks
UNION ALL SELECT 'favorites_total', count(*) FROM core_v2_preferences.favorites
UNION ALL SELECT 'play_history_total', count(*) FROM core_v2_playback.play_history
UNION ALL SELECT 'play_statistics_total', count(*) FROM core_v2_playback.play_statistics
UNION ALL SELECT 'track_features_total', count(*) FROM core_v2_ml.track_features
UNION ALL SELECT 'user_track_affinity_total', count(*) FROM core_v2_ml.user_track_affinity
UNION ALL SELECT 'karaoke_assets_total', count(*) FROM core_v2_karaoke.assets;

COMMIT;
