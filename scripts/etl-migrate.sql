-- ETL Migration Script: yay-tsa (public schema) → yay-tsa-core (core_v2_* schemas)
-- Run AFTER Flyway migrations have created all core_v2_* schemas
-- Run against the SAME database that has both old (public) and new (core_v2_*) schemas
--
-- Prerequisites:
--   1. pg_dump of production database as backup
--   2. Flyway migrations applied (all core_v2_* schemas exist with tables)
--   3. Extensions enabled: citext, pg_trgm, vector
--   4. pgcrypto extension (for token hashing via digest())
--
-- Idempotent: all INSERTs use ON CONFLICT DO NOTHING, safe to re-run.
-- Tier classification per Architecture Manifesto migration strategy.

\echo '=== Starting ETL Migration ==='
\echo ''

-- Required for digest() used in token hashing
CREATE EXTENSION IF NOT EXISTS pgcrypto;

BEGIN;

-- ============================================================
-- TIER 1: Irreplaceable data — must be migrated with zero loss
-- ============================================================

-- ------------------------------------------------------------
-- 1. Auth context
-- ------------------------------------------------------------

\echo '--- [Tier 1] Migrating core_v2_auth.users ---'
INSERT INTO core_v2_auth.users (
    id, username, password_hash, display_name, email,
    is_admin, is_active, created_at, updated_at, last_login_at, version
)
SELECT
    id,
    username,
    password_hash,
    display_name,
    email,
    COALESCE(is_admin, false),
    COALESCE(is_active, true),
    created_at,
    COALESCE(updated_at, created_at),
    last_login_at,
    0
FROM public.users
ON CONFLICT (id) DO NOTHING;

\echo '--- [Tier 1] Migrating core_v2_auth.api_tokens ---'
-- Tokens are SHA-256 hashed during migration for security.
-- The new system stores hashed tokens; raw tokens in the old DB are one-way converted.
INSERT INTO core_v2_auth.api_tokens (
    id, user_id, token, device_id, device_name,
    created_at, last_used_at, expires_at, revoked
)
SELECT
    id,
    user_id,
    encode(digest(token, 'sha256'), 'hex'),
    COALESCE(device_id::text, gen_random_uuid()::text),
    device_name,
    created_at,
    last_used_at,
    expires_at,
    COALESCE(revoked, false)
FROM public.api_tokens
ON CONFLICT (id) DO NOTHING;

-- ------------------------------------------------------------
-- 2. Playback context
-- ------------------------------------------------------------

\echo '--- [Tier 1] Migrating core_v2_playback.play_history ---'
INSERT INTO core_v2_playback.play_history (
    id, user_id, item_id, started_at, duration_ms,
    played_ms, completed, scrobbled, skipped
)
SELECT
    id,
    user_id::text,
    item_id::text,
    started_at,
    COALESCE(duration_ms, 0),
    COALESCE(played_ms, 0),
    COALESCE(completed, false),
    COALESCE(scrobbled, false),
    COALESCE(skipped, false)
FROM public.play_history
ON CONFLICT (id) DO NOTHING;

\echo '--- [Tier 1] Migrating core_v2_playback.play_statistics (from play_state) ---'
INSERT INTO core_v2_playback.play_statistics (
    user_id, item_id, play_count, last_played_at,
    playback_position_ms, is_favorite, updated_at
)
SELECT
    user_id::text,
    item_id::text,
    COALESCE(play_count, 0),
    last_played_at,
    COALESCE(playback_position_ms, 0),
    COALESCE(is_favorite, false),
    COALESCE(updated_at, now())
FROM public.play_state
ON CONFLICT (user_id, item_id) DO NOTHING;

-- NOTE: playback_sessions are NOT migrated (Tier 3 — disposable).
-- Old sessions (device-oriented) are incompatible with new lease-oriented model.

-- ------------------------------------------------------------
-- 3. Preferences context
-- ------------------------------------------------------------

\echo '--- [Tier 1] Migrating core_v2_preferences.user_preferences ---'
-- Create a user_preferences row for every user who has favorites or a preference contract.
INSERT INTO core_v2_preferences.user_preferences (user_id, version)
SELECT id::text, 0
FROM public.users
ON CONFLICT (user_id) DO NOTHING;

\echo '--- [Tier 1] Migrating core_v2_preferences.favorites (from play_state) ---'
INSERT INTO core_v2_preferences.favorites (
    user_id, track_id, favorited_at, position
)
SELECT
    user_id::text,
    item_id::text,
    COALESCE(updated_at, now()),
    (ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY COALESCE(updated_at, now())) - 1)::int
FROM public.play_state
WHERE is_favorite = true
ON CONFLICT (user_id, track_id) DO NOTHING;

\echo '--- [Tier 1] Migrating core_v2_preferences.preference_contracts ---'
-- Old schema: JSONB columns → New schema: TEXT columns
INSERT INTO core_v2_preferences.preference_contracts (
    user_id, hard_rules, soft_prefs, dj_style, red_lines, updated_at
)
SELECT
    user_id::text,
    COALESCE(hard_rules::text, ''),
    COALESCE(soft_prefs::text, ''),
    COALESCE(dj_style::text, ''),
    COALESCE(red_lines::text, ''),
    COALESCE(updated_at, now())
FROM public.user_preference_contract
ON CONFLICT (user_id) DO NOTHING;

-- ------------------------------------------------------------
-- 4. Playlists context
-- ------------------------------------------------------------

\echo '--- [Tier 1] Migrating core_v2_playlists.playlists ---'
INSERT INTO core_v2_playlists.playlists (
    id, owner, name, description, is_public,
    created_at, updated_at, version
)
SELECT
    id::text,
    user_id::text,
    name,
    description,
    COALESCE(is_public, false),
    created_at,
    COALESCE(updated_at, created_at),
    0
FROM public.playlists
ON CONFLICT (id) DO NOTHING;

\echo '--- [Tier 1] Migrating core_v2_playlists.playlist_tracks ---'
INSERT INTO core_v2_playlists.playlist_tracks (
    playlist_id, track_id, position, added_at
)
SELECT
    playlist_id::text,
    item_id::text,
    position,
    COALESCE(added_at, now())
FROM public.playlist_entries
ON CONFLICT (playlist_id, position) DO NOTHING;

-- ------------------------------------------------------------
-- 5. ML context
-- ------------------------------------------------------------

\echo '--- [Tier 1] Migrating core_v2_ml.track_features ---'
-- After Flyway V002, embedding columns are vector(N) types.
-- Old system also used vector() natively — lossless migration.
INSERT INTO core_v2_ml.track_features (
    track_id, bpm, bpm_confidence, musical_key, key_confidence,
    energy, loudness_integrated, loudness_range, average_loudness,
    valence, arousal, danceability, vocal_instrumental, voice_gender,
    spectral_complexity, dissonance, onset_rate, intro_duration_sec,
    embedding_discogs, embedding_musicnn, embedding_clap, embedding_mert,
    extracted_at, extractor_version
)
SELECT
    track_id,
    bpm, bpm_confidence, musical_key, key_confidence,
    energy, loudness_integrated, loudness_range, average_loudness,
    valence, arousal, danceability, vocal_instrumental, voice_gender,
    spectral_complexity, dissonance, onset_rate, intro_duration_sec,
    embedding_discogs::vector(1280),
    embedding_musicnn::vector(200),
    embedding_clap::vector(512),
    embedding_mert::vector(768),
    extracted_at,
    COALESCE(extractor_version, 'migrated-v1')
FROM public.track_features
ON CONFLICT (track_id) DO NOTHING;

\echo '--- [Tier 1] Migrating core_v2_ml.taste_profiles ---'
INSERT INTO core_v2_ml.taste_profiles (
    user_id, profile, summary_text, rebuilt_at,
    track_count, embedding_mert, embedding_clap
)
SELECT
    user_id,
    profile::text,
    summary_text,
    rebuilt_at,
    COALESCE(track_count, 0),
    embedding_mert::vector(768),
    embedding_clap::vector(512)
FROM public.taste_profile
ON CONFLICT (user_id) DO NOTHING;

\echo '--- [Tier 1] Migrating core_v2_ml.user_track_affinity ---'
INSERT INTO core_v2_ml.user_track_affinity (
    user_id, track_id, affinity_score,
    play_count, completion_count, skip_count,
    thumbs_up_count, thumbs_down_count, total_listen_sec,
    last_signal_at, updated_at
)
SELECT
    user_id,
    track_id,
    COALESCE(affinity_score, 0),
    COALESCE(play_count, 0),
    COALESCE(completion_count, 0),
    COALESCE(skip_count, 0),
    COALESCE(thumbs_up_count, 0),
    COALESCE(thumbs_down_count, 0),
    COALESCE(total_listen_sec, 0),
    last_signal_at,
    COALESCE(updated_at, now())
FROM public.user_track_affinity
ON CONFLICT (user_id, track_id) DO NOTHING;

-- ------------------------------------------------------------
-- 6. Adaptive context
-- ------------------------------------------------------------

\echo '--- [Tier 1] Migrating core_v2_adaptive.playback_signals ---'
-- playback_signals reference listening_sessions via FK.
-- Migrate sessions first if they exist in the old schema; signals reference them.
-- Old system may not have listening_sessions — signals may reference session_ids
-- that must exist in the new schema. Create stub sessions for orphan references.

DO $$
BEGIN
    -- Check if old playback_signal table exists
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = 'public' AND table_name = 'playback_signal') THEN

        -- Create stub listening sessions for any session_ids referenced by signals
        -- that don't already exist in the new listening_sessions table
        INSERT INTO core_v2_adaptive.listening_sessions (
            id, user_id, state, started_at, last_activity_at,
            attention_mode, version
        )
        SELECT DISTINCT
            ps.session_id,
            COALESCE(ps.context->>'userId', '00000000-0000-0000-0000-000000000000')::uuid,
            'ENDED',
            MIN(ps.created_at) OVER (PARTITION BY ps.session_id),
            MAX(ps.created_at) OVER (PARTITION BY ps.session_id),
            'PASSIVE',
            0
        FROM public.playback_signal ps
        WHERE NOT EXISTS (
            SELECT 1 FROM core_v2_adaptive.listening_sessions ls
            WHERE ls.id = ps.session_id
        )
        ON CONFLICT (id) DO NOTHING;

        -- Now migrate the signals
        INSERT INTO core_v2_adaptive.playback_signals (
            id, session_id, track_id, queue_entry_id,
            signal_type, context, created_at
        )
        SELECT
            id, session_id, track_id, queue_entry_id,
            signal_type, context, created_at
        FROM public.playback_signal
        ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

\echo '--- [Tier 1] Migrating core_v2_adaptive.llm_decisions ---'
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = 'public' AND table_name = 'llm_decision_log') THEN
        INSERT INTO core_v2_adaptive.llm_decisions (
            id, session_id, trigger_type, trigger_signal_id, prompt_hash,
            prompt_tokens, completion_tokens, model_id, latency_ms,
            intent, edits, base_queue_version, applied_queue_version,
            validation_result, validation_details, created_at
        )
        SELECT
            id, session_id, trigger_type, trigger_signal_id, prompt_hash,
            prompt_tokens, completion_tokens, model_id, latency_ms,
            intent, edits, base_queue_version, applied_queue_version,
            validation_result, validation_details, created_at
        FROM public.llm_decision_log
        ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

-- ============================================================
-- TIER 2: Reproducible data — best-effort, can be rescanned/reprocessed
-- ============================================================

-- ------------------------------------------------------------
-- 7. Library context
-- ------------------------------------------------------------

\echo '--- [Tier 2] Migrating library: artists ---'
INSERT INTO core_v2_library.entities (
    id, entity_type, name, sort_name, parent_id,
    source_path, search_text, created_at, updated_at
)
SELECT
    i.id,
    'ARTIST',
    i.name,
    LOWER(i.name),
    NULL,
    'artist:' || LOWER(COALESCE(i.name, 'unknown')),
    LOWER(COALESCE(i.name, '')),
    i.created_at,
    COALESCE(i.updated_at, i.created_at)
FROM public.items i
WHERE i.type = 'MusicArtist'
ON CONFLICT (id) DO NOTHING;

INSERT INTO core_v2_library.artists (entity_id, musicbrainz_id, biography)
SELECT
    a.item_id,
    a.musicbrainz_id,
    a.biography
FROM public.artists a
WHERE EXISTS (SELECT 1 FROM core_v2_library.entities e WHERE e.id = a.item_id)
ON CONFLICT (entity_id) DO NOTHING;

\echo '--- [Tier 2] Migrating library: albums ---'
INSERT INTO core_v2_library.entities (
    id, entity_type, name, sort_name, parent_id,
    source_path, search_text, created_at, updated_at
)
SELECT
    i.id,
    'ALBUM',
    i.name,
    LOWER(i.name),
    i.parent_id,
    'album:' || LOWER(COALESCE(p.name, 'unknown')) || ':' || LOWER(COALESCE(i.name, 'unknown')),
    LOWER(COALESCE(i.name, '')),
    i.created_at,
    COALESCE(i.updated_at, i.created_at)
FROM public.items i
LEFT JOIN public.items p ON i.parent_id = p.id
WHERE i.type = 'MusicAlbum'
ON CONFLICT (id) DO NOTHING;

INSERT INTO core_v2_library.albums (
    entity_id, artist_id, release_date,
    total_tracks, total_discs, is_complete
)
SELECT
    a.item_id,
    a.artist_id,
    a.release_date,
    a.total_tracks,
    a.total_discs,
    COALESCE(a.is_complete, false)
FROM public.albums a
WHERE EXISTS (SELECT 1 FROM core_v2_library.entities e WHERE e.id = a.item_id)
ON CONFLICT (entity_id) DO NOTHING;

\echo '--- [Tier 2] Migrating library: tracks ---'
INSERT INTO core_v2_library.entities (
    id, entity_type, name, sort_name, parent_id,
    source_path, container, size_bytes, search_text,
    created_at, updated_at
)
SELECT
    i.id,
    'TRACK',
    i.name,
    LOWER(i.name),
    i.parent_id,
    i.path,
    at.codec,
    i.size_bytes,
    LOWER(COALESCE(i.name, '') || ' ' || COALESCE(i.search_text, '')),
    i.created_at,
    COALESCE(i.updated_at, i.created_at)
FROM public.items i
LEFT JOIN public.audio_tracks at ON i.id = at.item_id
WHERE i.type = 'AudioTrack'
ON CONFLICT (id) DO NOTHING;

INSERT INTO core_v2_library.audio_tracks (
    entity_id, album_id, album_artist_id,
    track_number, disc_number, duration_ms,
    bitrate, sample_rate, channels,
    year, codec
)
SELECT
    at.item_id,
    at.album_id,
    at.album_artist_id,
    at.track_number,
    at.disc_number,
    at.duration_ms,
    at.bitrate,
    at.sample_rate,
    at.channels,
    at.year,
    at.codec
FROM public.audio_tracks at
WHERE EXISTS (SELECT 1 FROM core_v2_library.entities e WHERE e.id = at.item_id)
ON CONFLICT (entity_id) DO NOTHING;

\echo '--- [Tier 2] Migrating library: genres ---'
INSERT INTO core_v2_library.genres (id, name)
SELECT id, name
FROM public.genres
ON CONFLICT (id) DO NOTHING;

INSERT INTO core_v2_library.entity_genres (entity_id, genre_id)
SELECT item_id, genre_id
FROM public.item_genres
WHERE EXISTS (SELECT 1 FROM core_v2_library.entities e WHERE e.id = item_id)
  AND EXISTS (SELECT 1 FROM core_v2_library.genres g WHERE g.id = genre_id)
ON CONFLICT (entity_id, genre_id) DO NOTHING;

\echo '--- [Tier 2] Migrating library: images ---'
INSERT INTO core_v2_library.images (
    id, entity_id, image_type, path, is_primary
)
SELECT
    id,
    item_id,
    image_type::text,
    path,
    COALESCE(is_primary, false)
FROM public.images
WHERE EXISTS (SELECT 1 FROM core_v2_library.entities e WHERE e.id = item_id)
ON CONFLICT (id) DO NOTHING;

-- ------------------------------------------------------------
-- 8. Karaoke context
-- ------------------------------------------------------------

\echo '--- [Tier 2] Migrating core_v2_karaoke.assets ---'
-- Karaoke readiness data lives alongside audio_tracks in the old schema.
-- Only tracks with karaoke artifacts or explicit readiness flag are migrated.
INSERT INTO core_v2_karaoke.assets (
    track_id, instrumental_path, vocal_path, ready_at
)
SELECT
    at.item_id,
    at.instrumental_path,
    at.vocal_path,
    CASE WHEN at.karaoke_ready THEN now() ELSE NULL END
FROM public.audio_tracks at
WHERE at.karaoke_ready = true
   OR at.instrumental_path IS NOT NULL
   OR at.vocal_path IS NOT NULL
ON CONFLICT (track_id) DO NOTHING;

-- ============================================================
-- TIER 3: Disposable data — NOT migrated
-- ============================================================

-- The following old tables are intentionally NOT migrated:
--   public.sessions          → incompatible schema (device-oriented vs lease-oriented)
--   public.library_scans     → scan tracking removed; rescanner will rebuild
--   public.feature_extraction_job → job queue removed; ML worker manages its own queue
--   public.app_settings      → no equivalent in new schema

COMMIT;

\echo ''
\echo '=== ETL Migration Complete ==='
\echo ''
\echo 'Next steps:'
\echo '  1. Run: psql -f scripts/validate-migration.sql'
\echo '  2. Compare row counts between old and new schemas'
\echo '  3. Spot-check key entities (users, top playlists, favorites)'
\echo '  4. Verify embedding dimensions in core_v2_ml.track_features'
\echo ''
