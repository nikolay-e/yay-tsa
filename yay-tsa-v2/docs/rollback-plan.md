# Big Bang Migration — Rollback Plan

## Pre-Cutover Checklist

1. [ ] `pg_dump` of production database (full)
2. [ ] Old application Docker image tagged and saved
3. [ ] DNS TTL lowered to 60s (24h before cutover)
4. [ ] New system dry-run against production data copy — validated
5. [ ] Monitoring dashboards configured for new system

## Cutover Procedure

1. Freeze old system (read-only mode or shutdown)
2. `pg_dump` final snapshot
3. Run ETL migration script → new `core_v2_*` schemas
4. Run validation queries (row counts, checksums)
5. Start new application
6. Smoke test: login, browse, play, search, favorites
7. Switch DNS / deploy new ingress
8. Monitor for 1 hour

## Rollback Triggers

Immediate rollback if ANY of:

- Login fails for existing users
- Library empty or missing >1% of tracks
- Audio streaming returns 404/500
- Play history data missing (play counts = 0 for users with history)
- Favorites lost

## Rollback Procedure (< 1 hour)

1. Switch DNS / ingress back to old system
2. Start old application against original database
3. Drop `core_v2_*` schemas (optional — can leave for investigation)
4. Restore DNS TTL
5. Post-mortem: document what failed, fix, retry

## Point of No Return

None — the old database is never modified during cutover.
The old system can always be restored from the pre-cutover `pg_dump`.

## Validation Queries (post-migration)

```sql
-- Row counts
SELECT 'users' as tbl, count(*) FROM core_v2_auth.users
UNION ALL SELECT 'tracks', count(*) FROM core_v2_library.entities WHERE entity_type = 'TRACK'
UNION ALL SELECT 'playlists', count(*) FROM core_v2_playlists.playlists
UNION ALL SELECT 'play_history', count(*) FROM core_v2_playback.play_history
UNION ALL SELECT 'favorites', count(*) FROM core_v2_preferences.favorites
UNION ALL SELECT 'track_features', count(*) FROM core_v2_ml.track_features
UNION ALL SELECT 'taste_profiles', count(*) FROM core_v2_ml.taste_profiles;

-- Compare with old system counts
-- SELECT 'old_users', count(*) FROM public.users
-- UNION ALL ...
```
