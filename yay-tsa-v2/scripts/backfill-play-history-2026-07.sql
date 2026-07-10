-- One-off data repair for core_v2_playback.play_history (2026-07). NOT a Flyway
-- migration — run manually after deploying the ScrobbleService fix:
--   kubectl exec -n shared-database shared-postgres-1 -- psql -d yaytsa_production \
--     -f - < yay-tsa-v2/scripts/backfill-play-history-2026-07.sql
--
-- Provenance notes:
-- * Rows with recorded_at on 2026-06-14 are a one-day legacy import from the retired v1
--   backend. They arrived with duration_ms already set but FOREIGN completed/skipped
--   semantics (64% skipped vs the live path's ~28%); step 2 normalizes them under the
--   same position-based rule as everything else so the flag series is consistent
--   end-to-end and no fake June "improvement" shows up in windowed trends.
-- * Every row written by the live path before this fix has duration_ms = 0 because
--   ScrobbleService hardcoded durationMs = null; step 1 backfills it from the library.
-- * Tracks deleted or re-identified by a rescan no longer join the library: those rows
--   keep duration_ms = 0 and are NOT recomputed (completion ratio stays unknowable).
-- * Rows with played_ms = 0 are indeterminate (no position on the wire) and are left
--   untouched.

BEGIN;

-- Step 1: backfill duration_ms from the library (join via text to avoid uuid-cast
-- failures on any malformed historical item_id).
UPDATE core_v2_playback.play_history ph
SET duration_ms = tr.duration_ms
FROM core_v2_library.audio_tracks tr
WHERE tr.entity_id::text = ph.item_id
  AND ph.duration_ms = 0
  AND COALESCE(tr.duration_ms, 0) > 0;

-- Step 2: recompute completed/skipped uniformly under the position-based rule
-- (completed = played >= half the track; skipped = not completed AND played >= 3 s;
-- sub-3 s = queue-surfing noise, neither flag). Mirrors ScrobbleService post-fix.
UPDATE core_v2_playback.play_history
SET completed = played_ms >= duration_ms / 2,
    skipped   = played_ms < duration_ms / 2 AND played_ms >= 3000
WHERE duration_ms > 0
  AND played_ms > 0;

COMMIT;

-- Verification (run after commit):
--   SELECT count(*) FILTER (WHERE duration_ms = 0)  AS still_missing_duration,
--          count(*) FILTER (WHERE skipped)          AS skipped_rows,
--          count(*) FILTER (WHERE completed)        AS completed_rows,
--          count(*)                                 AS total
--   FROM core_v2_playback.play_history;
