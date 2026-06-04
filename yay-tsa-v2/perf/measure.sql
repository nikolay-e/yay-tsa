\set ON_ERROR_STOP on
SET search_path = core_v2_library, core_v2_preferences, public;
\set deepalb (:albums - 50)
\set deepfav (:favs - 50)
-- warm
SELECT count(*) FROM core_v2_library.entities; SELECT count(*) FROM core_v2_preferences.favorites;

\echo @@Q ALBUMS_browse_sortname_off0
EXPLAIN (ANALYZE,BUFFERS,TIMING) SELECT * FROM core_v2_library.entities WHERE entity_type='ALBUM' ORDER BY sort_name OFFSET 0 LIMIT 50;
\echo @@Q ALBUMS_browse_sortname_deepoffset
EXPLAIN (ANALYZE,BUFFERS,TIMING) SELECT * FROM core_v2_library.entities WHERE entity_type='ALBUM' ORDER BY sort_name OFFSET :deepalb LIMIT 50;
\echo @@Q SONGS_browse_sortname_off0
EXPLAIN (ANALYZE,BUFFERS,TIMING) SELECT * FROM core_v2_library.entities WHERE entity_type='TRACK' ORDER BY sort_name OFFSET 0 LIMIT 50;
\echo @@Q SONGS_recentlyadded_created_at_desc
EXPLAIN (ANALYZE,BUFFERS,TIMING) SELECT * FROM core_v2_library.entities WHERE entity_type='TRACK' ORDER BY created_at DESC OFFSET 0 LIMIT 50;
\echo @@Q SONGS_recentlyadded_created_at_id_tiebreak
EXPLAIN (ANALYZE,BUFFERS,TIMING) SELECT * FROM core_v2_library.entities WHERE entity_type='TRACK' ORDER BY created_at DESC, id DESC OFFSET 0 LIMIT 50;
\echo @@Q FAVORITES_loadALL_orderby_position
EXPLAIN (ANALYZE,BUFFERS,TIMING) SELECT * FROM core_v2_preferences.favorites WHERE user_id='u1' ORDER BY position;
\echo @@Q FAVORITES_sqlpage_off0
EXPLAIN (ANALYZE,BUFFERS,TIMING) SELECT * FROM core_v2_preferences.favorites WHERE user_id='u1' ORDER BY position OFFSET 0 LIMIT 50;
\echo @@Q FAVORITES_sqlpage_deepoffset
EXPLAIN (ANALYZE,BUFFERS,TIMING) SELECT * FROM core_v2_preferences.favorites WHERE user_id='u1' ORDER BY position OFFSET :deepfav LIMIT 50;
\echo @@Q SEARCH_name_ilike_trgm
EXPLAIN (ANALYZE,BUFFERS,TIMING) SELECT * FROM core_v2_library.entities WHERE name ILIKE '%Track 12345%' LIMIT 50;
