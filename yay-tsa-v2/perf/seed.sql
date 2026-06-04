-- Seeds a realistic library + favorites at a parameterised scale.
-- Vars: :artists :albums :tracks :favs   (e.g. psql -v artists=1000 -v albums=2000 -v tracks=17000 -v favs=2000)
-- Tracks get created_at with deliberate ties (groups of 10 share a timestamp) so the tie-breaker
-- behaviour of recently-added pagination can be exercised.
\set ON_ERROR_STOP on
SET client_min_messages = warning;
SET search_path = core_v2_library, core_v2_preferences, public;
TRUNCATE core_v2_library.entities CASCADE;
TRUNCATE core_v2_preferences.favorites;
BEGIN;

CREATE TEMP TABLE art(n int, id uuid) ON COMMIT DROP;
INSERT INTO art SELECT g, gen_random_uuid() FROM generate_series(1, :artists) g;
INSERT INTO core_v2_library.entities(id, entity_type, name, sort_name, source_path, search_text, created_at)
  SELECT id, 'ARTIST', 'Artist ' || n, 'artist ' || lpad(n::text, 7, '0'), '/a/' || n, 'artist ' || n, now() FROM art;
INSERT INTO core_v2_library.artists(entity_id) SELECT id FROM art;

CREATE TEMP TABLE alb(n int, id uuid, artist_id uuid) ON COMMIT DROP;
INSERT INTO alb SELECT g, gen_random_uuid(), a.id
  FROM generate_series(1, :albums) g JOIN art a ON a.n = 1 + (g % :artists);
INSERT INTO core_v2_library.entities(id, entity_type, name, sort_name, source_path, search_text, created_at)
  SELECT id, 'ALBUM', 'Album ' || n, 'album ' || lpad(n::text, 7, '0'), '/al/' || n, 'album ' || n, now() FROM alb;
INSERT INTO core_v2_library.albums(entity_id, artist_id, total_tracks) SELECT id, artist_id, 10 FROM alb;
INSERT INTO core_v2_library.images(id, entity_id, image_type, path, is_primary)
  SELECT gen_random_uuid(), id, 'Primary', '/img/' || n || '.jpg', true FROM alb;
INSERT INTO core_v2_library.images(id, entity_id, image_type, path, is_primary)
  SELECT gen_random_uuid(), id, 'Primary', '/img/a' || n || '.jpg', true FROM art;

CREATE TEMP TABLE trk(n int, id uuid, album_id uuid, artist_id uuid) ON COMMIT DROP;
INSERT INTO trk SELECT g, gen_random_uuid(), al.id, al.artist_id
  FROM generate_series(1, :tracks) g JOIN alb al ON al.n = 1 + (g % :albums);
INSERT INTO core_v2_library.entities(id, entity_type, name, sort_name, source_path, search_text, created_at)
  SELECT id, 'TRACK', 'Track ' || n, 'track ' || lpad(n::text, 7, '0'), '/t/' || n, 'track ' || n,
         now() - ((n / 10) || ' seconds')::interval FROM trk;
INSERT INTO core_v2_library.audio_tracks(entity_id, album_id, album_artist_id, track_number, disc_number, duration_ms)
  SELECT id, album_id, artist_id, 1 + (n % 12), 1, 180000 FROM trk;

INSERT INTO core_v2_preferences.user_preferences(user_id, version) VALUES ('u1', 0) ON CONFLICT (user_id) DO NOTHING;
INSERT INTO core_v2_preferences.favorites(user_id, track_id, favorited_at, position)
  SELECT 'u1', t.id::text, now() - (t.n || ' minutes')::interval, t.n - 1 FROM trk t WHERE t.n <= :favs;

COMMIT;
ANALYZE core_v2_library.entities;
ANALYZE core_v2_library.albums;
ANALYZE core_v2_library.audio_tracks;
ANALYZE core_v2_library.images;
ANALYZE core_v2_preferences.favorites;
