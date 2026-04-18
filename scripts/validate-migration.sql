-- Post-migration validation script
-- Run after ETL migration to verify data integrity

\echo '=== Row Count Validation ==='

SELECT 'auth.users' AS table_name, count(*) AS row_count FROM core_v2_auth.users
UNION ALL SELECT 'auth.api_tokens', count(*) FROM core_v2_auth.api_tokens
UNION ALL SELECT 'library.entities (TRACK)', count(*) FROM core_v2_library.entities WHERE entity_type = 'TRACK'
UNION ALL SELECT 'library.entities (ALBUM)', count(*) FROM core_v2_library.entities WHERE entity_type = 'ALBUM'
UNION ALL SELECT 'library.entities (ARTIST)', count(*) FROM core_v2_library.entities WHERE entity_type = 'ARTIST'
UNION ALL SELECT 'library.genres', count(*) FROM core_v2_library.genres
UNION ALL SELECT 'library.images', count(*) FROM core_v2_library.images
UNION ALL SELECT 'playback.play_history', count(*) FROM core_v2_playback.play_history
UNION ALL SELECT 'playback.play_statistics', count(*) FROM core_v2_playback.play_statistics
UNION ALL SELECT 'playlists.playlists', count(*) FROM core_v2_playlists.playlists
UNION ALL SELECT 'playlists.playlist_tracks', count(*) FROM core_v2_playlists.playlist_tracks
UNION ALL SELECT 'preferences.favorites', count(*) FROM core_v2_preferences.favorites
UNION ALL SELECT 'preferences.preference_contracts', count(*) FROM core_v2_preferences.preference_contracts
UNION ALL SELECT 'ml.track_features', count(*) FROM core_v2_ml.track_features
UNION ALL SELECT 'ml.taste_profiles', count(*) FROM core_v2_ml.taste_profiles
UNION ALL SELECT 'ml.user_track_affinity', count(*) FROM core_v2_ml.user_track_affinity
UNION ALL SELECT 'adaptive.listening_sessions', count(*) FROM core_v2_adaptive.listening_sessions
UNION ALL SELECT 'adaptive.playback_signals', count(*) FROM core_v2_adaptive.playback_signals
UNION ALL SELECT 'adaptive.llm_decisions', count(*) FROM core_v2_adaptive.llm_decisions
UNION ALL SELECT 'karaoke.assets', count(*) FROM core_v2_karaoke.assets
ORDER BY table_name;

\echo ''
\echo '=== Data Integrity Checks ==='

-- Users with no API tokens (should be zero after migration if all had tokens)
SELECT 'users_without_tokens' AS check_name,
    count(*) AS count
FROM core_v2_auth.users u
WHERE NOT EXISTS (SELECT 1 FROM core_v2_auth.api_tokens t WHERE t.user_id = u.id);

-- Orphan albums (no tracks pointing to them)
SELECT 'orphan_albums' AS check_name,
    count(*) AS count
FROM core_v2_library.entities e
WHERE e.entity_type = 'ALBUM'
AND NOT EXISTS (
    SELECT 1 FROM core_v2_library.entities child
    WHERE child.parent_id = e.id
);

-- Orphan artists (no albums pointing to them)
SELECT 'orphan_artists' AS check_name,
    count(*) AS count
FROM core_v2_library.entities e
WHERE e.entity_type = 'ARTIST'
AND NOT EXISTS (
    SELECT 1 FROM core_v2_library.entities child
    WHERE child.parent_id = e.id
);

-- Favorites referencing non-existent tracks
SELECT 'dangling_favorites' AS check_name,
    count(*) AS count
FROM core_v2_preferences.favorites f
WHERE NOT EXISTS (
    SELECT 1 FROM core_v2_library.entities e
    WHERE e.id = f.track_id::uuid
);

-- Playlist tracks referencing non-existent tracks
SELECT 'dangling_playlist_tracks' AS check_name,
    count(*) AS count
FROM core_v2_playlists.playlist_tracks pt
WHERE NOT EXISTS (
    SELECT 1 FROM core_v2_library.entities e
    WHERE e.id = pt.track_id::uuid
);

-- Track features without corresponding library entry
SELECT 'orphan_track_features' AS check_name,
    count(*) AS count
FROM core_v2_ml.track_features tf
WHERE NOT EXISTS (
    SELECT 1 FROM core_v2_library.entities e
    WHERE e.id = tf.track_id
);

-- Play statistics spot check (top 5 users by play count)
\echo ''
\echo '=== Play Statistics Spot Check ==='
SELECT user_id, sum(play_count) AS total_plays, count(*) AS unique_tracks
FROM core_v2_playback.play_statistics
GROUP BY user_id
ORDER BY total_plays DESC
LIMIT 5;

-- Embedding dimensions check
\echo ''
\echo '=== Embedding Dimensions ==='
SELECT 'discogs' AS embedding, avg(array_length(embedding_discogs::float4[], 1)) AS avg_dim
FROM core_v2_ml.track_features WHERE embedding_discogs IS NOT NULL
UNION ALL
SELECT 'musicnn', avg(array_length(embedding_musicnn::float4[], 1))
FROM core_v2_ml.track_features WHERE embedding_musicnn IS NOT NULL
UNION ALL
SELECT 'clap', avg(array_length(embedding_clap::float4[], 1))
FROM core_v2_ml.track_features WHERE embedding_clap IS NOT NULL
UNION ALL
SELECT 'mert', avg(array_length(embedding_mert::float4[], 1))
FROM core_v2_ml.track_features WHERE embedding_mert IS NOT NULL;

\echo ''
\echo '=== Validation Complete ==='
