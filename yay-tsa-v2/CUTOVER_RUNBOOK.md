# Cutover Runbook — yay-tsa v1 → v2

Single-operator runbook for migrating production from `yay-tsa-production` (v1) to `yay-tsa-v2-production` (v2). Executed manually with `kubectl` + `psql`. Target downtime: **< 5 minutes** (ingress flip only).

- Database: shared CNPG cluster `shared-postgres` in namespace `shared-database`, database `yaytsa_production`.
- v1 ingress host: `yay-tsa.com` → Service `yay-tsa-production-backend` in `yay-tsa-production`.
- v2 target namespace: `yay-tsa-v2-production`.

> Architectural note: ETL writes into new `core_v2_*` schemas in the **same** `yaytsa_production` DB. The legacy `public.*` schema is never modified. This is the point-of-no-return safety net — see `docs/rollback-plan.md`.

---

## ETL Coverage Map

### Source `public.*` → Target `core_v2_*`

| Source (public)                 | Target schema.table                                             | Tier | Notes                                                                                                 |
| ------------------------------- | --------------------------------------------------------------- | ---- | ----------------------------------------------------------------------------------------------------- |
| `users`                         | `core_v2_auth.users`                                            | 1    | Direct copy + version=0                                                                               |
| `api_tokens`                    | `core_v2_auth.api_tokens`                                       | 1    | **Token SHA-256 hashed during ETL** — old raw tokens become invalid post-cutover; users must re-login |
| `play_history`                  | `core_v2_playback.play_history`                                 | 1    | UUID → text cast on user_id/item_id                                                                   |
| `play_state`                    | `core_v2_playback.play_statistics`                              | 1    | Split: play counters → play_statistics                                                                |
| `play_state` (is_favorite=true) | `core_v2_preferences.favorites`                                 | 1    | Same row split: favorites with derived `position` via ROW_NUMBER                                      |
| `users` (synthetic row)         | `core_v2_preferences.user_preferences`                          | 1    | One row per user                                                                                      |
| `user_preference_contract`      | `core_v2_preferences.preference_contracts`                      | 1    | **JSONB → TEXT** transformation                                                                       |
| `playlists`                     | `core_v2_playlists.playlists`                                   | 1    | `user_id` → `owner` (text)                                                                            |
| `playlist_entries`              | `core_v2_playlists.playlist_tracks`                             | 1    | `item_id` → `track_id`                                                                                |
| `track_features`                | `core_v2_ml.track_features`                                     | 1    | **vector cast** to (1280/200/512/768) — must match Flyway V002 dims                                   |
| `taste_profile`                 | `core_v2_ml.taste_profiles`                                     | 1    | JSONB → TEXT for `profile`                                                                            |
| `user_track_affinity`           | `core_v2_ml.user_track_affinity`                                | 1    | Direct copy with COALESCE defaults                                                                    |
| `playback_signal`               | `core_v2_adaptive.playback_signals` + stub `listening_sessions` | 1    | Conditional (table may not exist); creates ENDED stub sessions for orphan session_ids                 |
| `llm_decision_log`              | `core_v2_adaptive.llm_decisions`                                | 1    | Conditional (table may not exist)                                                                     |
| `items` (MusicArtist)           | `core_v2_library.entities` (ARTIST)                             | 2    | Polymorphic entity with discriminator                                                                 |
| `artists`                       | `core_v2_library.artists`                                       | 2    | Specialization table                                                                                  |
| `items` (MusicAlbum)            | `core_v2_library.entities` (ALBUM)                              | 2    | parent_id preserved                                                                                   |
| `albums`                        | `core_v2_library.albums`                                        | 2    |                                                                                                       |
| `items` (AudioTrack)            | `core_v2_library.entities` (TRACK)                              | 2    | Joins `audio_tracks` for codec/container                                                              |
| `audio_tracks`                  | `core_v2_library.audio_tracks`                                  | 2    |                                                                                                       |
| `genres` / `item_genres`        | `core_v2_library.genres` / `entity_genres`                      | 2    |                                                                                                       |
| `images`                        | `core_v2_library.images`                                        | 2    |                                                                                                       |
| `audio_tracks` (karaoke cols)   | `core_v2_karaoke.assets`                                        | 2    | Filtered: only rows with `karaoke_ready` or instrumental/vocal paths                                  |

### Intentionally NOT Migrated (Tier 3)

`public.sessions`, `public.library_scans`, `public.feature_extraction_job`, `public.app_settings`. Acceptable: rescanner + ML worker rebuild on the new side.

### Gaps — Not Covered by ETL (verify before cutover)

These are **silently missing** from `etl-migrate.sql`. If any of these tables exist in current production `public.*`, decide before cutover whether to extend ETL or accept loss:

- **Group sync tables (V31/V32)** — listening groups, group membership, group presence. Not present in ETL. If groups are in use in v1, user state will be lost.
- **Karaoke fail counts (V34)** — separation attempt counters / failure backoff state. Only success artifacts via `karaoke_ready` are migrated.
- **Audio fingerprints** — any AcoustID / Chromaprint tables. Not referenced.
- **Additional ML embeddings** — only Discogs/MusicNN/CLAP/MERT carried over. Any custom or experimental vector columns are dropped.
- **Outbox / notification queue** — not migrated; assumed drained before freeze.
- **Image binary blobs / image cache** — only `path` references are migrated; nginx/disk cache regenerates.

### Risky Transformations (manual spot-check post-ETL)

1. **`api_tokens.token` SHA-256 hash** — irreversible. All clients (PWA, Symfonium, etc.) must re-authenticate after cutover. Communicate this before freeze.
2. **JSONB → TEXT** on `user_preference_contract` and `taste_profile.profile` — verify v2 reads parse correctly (json-in-text round-trip).
3. **`vector` recasts** to fixed dimensions — if any old row has dim mismatch, INSERT will fail. Pre-flight: `SELECT count(*) FROM public.track_features WHERE vector_dims(embedding_mert) <> 768;` etc.
4. **`ROW_NUMBER()` for favorites position** — order is by `updated_at`; ties are non-deterministic. Acceptable for favorites but note in case of bug reports about ordering.
5. **Stub `listening_sessions` for orphan `playback_signal.session_id`** — fabricated user_id falls back to `00000000-...-0000` if `ps.context->>'userId'` is null. These rows are "ENDED" stubs; functional but cosmetically weird.
6. **`item_id::text` casts** in playback tables — assumes UUIDs; non-UUID item ids would break. UUID format is current schema norm.

---

## Pre-Flight (T-24h to T-1h)

### 1. Verify CNPG backup is recent

```bash
kubectl -n shared-database get cluster shared-postgres -o jsonpath='{.status.firstRecoverabilityPoint}{"\n"}{.status.lastSuccessfulBackup}{"\n"}'
kubectl -n shared-database get backups.postgresql.cnpg.io -o wide
```

Require: `lastSuccessfulBackup` within last 24h.

### 2. Verify v2 shadow run on staging

- v2 has been running against staging for at least the agreed shadow period (target: 7 days).
- Staging ETL dry-run completed; row counts within tolerance vs source.
- Smoke E2E passing on staging.

### 3. Verify Flyway migrations applied in production DB

```bash
kubectl -n shared-database exec -it shared-postgres-1 -- \
  psql -U postgres -d yaytsa_production \
  -c "\dn core_v2_*"

kubectl -n shared-database exec -it shared-postgres-1 -- \
  psql -U postgres -d yaytsa_production \
  -c "SELECT version, description, success FROM flyway_schema_history WHERE installed_rank > 0 ORDER BY installed_rank DESC LIMIT 20;"
```

All `core_v2_*` schemas must exist; all migrations `success = true`. **Deploy v2 backend to `yay-tsa-v2-production` with `replicas: 0`** beforehand so the Flyway init container applies migrations without serving traffic.

### 4. Lower DNS TTL to 60s

In Cloudflare DNS for `yay-tsa.com` — set TTL = 60 (or "Auto"). Do this 24h ahead so propagation completes.

### 5. Pre-flight data sanity

```bash
kubectl -n shared-database exec -it shared-postgres-1 -- \
  psql -U postgres -d yaytsa_production <<'SQL'
SELECT 'users' tbl, count(*) FROM public.users
UNION ALL SELECT 'items_track', count(*) FROM public.items WHERE type='AudioTrack'
UNION ALL SELECT 'play_history', count(*) FROM public.play_history
UNION ALL SELECT 'play_state_fav', count(*) FROM public.play_state WHERE is_favorite=true
UNION ALL SELECT 'track_features', count(*) FROM public.track_features
UNION ALL SELECT 'playlists', count(*) FROM public.playlists;

SELECT 'mert_bad_dims' tbl, count(*) FROM public.track_features
  WHERE embedding_mert IS NOT NULL AND vector_dims(embedding_mert) <> 768;
SQL
```

Record the counts — you compare them against post-ETL counts in step 5.

---

## Cutover Procedure (T-0)

### Step 1 — Take ad-hoc CNPG backup

```bash
cat <<'YAML' | kubectl apply -f -
apiVersion: postgresql.cnpg.io/v1
kind: Backup
metadata:
  name: pre-yaytsa-v2-cutover-$(date +%Y%m%d-%H%M)
  namespace: shared-database
spec:
  cluster:
    name: shared-postgres
  method: barmanObjectStore
YAML

# Wait for completion
kubectl -n shared-database get backups.postgresql.cnpg.io -w
```

Wait until status `completed` before proceeding. **Do not skip this** — this is your rollback floor if anything corrupts `public.*` (it shouldn't, but the backup is cheap insurance).

### Step 2 — Freeze v1 writes

Scale v1 backend to 1 replica and flip nginx to a read-only / maintenance mode is the cleanest option. Simplest: scale to 0 and accept the few minutes of full downtime.

```bash
# Capture current scale for rollback
kubectl -n yay-tsa-production get deploy yay-tsa-production-backend -o jsonpath='{.spec.replicas}' > /tmp/v1-replicas.txt
cat /tmp/v1-replicas.txt

# Freeze
kubectl -n yay-tsa-production scale deploy yay-tsa-production-backend --replicas=0

# Verify no pods
kubectl -n yay-tsa-production get pods -l app=yay-tsa-production-backend
```

Note T-0 timestamp: `date -u +%FT%TZ` — used for monitoring window.

### Step 3 — Run ETL

```bash
kubectl -n shared-database exec -i shared-postgres-1 -- \
  psql -U postgres -d yaytsa_production -v ON_ERROR_STOP=1 \
  < /Users/nikolay/yay-tsa/yay-tsa-v2/scripts/etl-migrate.sql \
  2>&1 | tee /tmp/etl-$(date +%Y%m%d-%H%M).log
```

`ON_ERROR_STOP=1` aborts on first error — the ETL is wrapped in a single `BEGIN/COMMIT`, so failure rolls back cleanly.

Expected: every `\echo` block shows progress; final `=== ETL Migration Complete ===`.

### Step 4 — Run validation

```bash
kubectl -n shared-database exec -i shared-postgres-1 -- \
  psql -U postgres -d yaytsa_production \
  < /Users/nikolay/yay-tsa/yay-tsa-v2/scripts/validate-migration.sql \
  2>&1 | tee /tmp/validate-$(date +%Y%m%d-%H%M).log
```

**Expected results:**

- `=== Row Count Validation ===` — compare each count to pre-flight numbers (step 5). Library entities counts should match `items` filtered by `type`. `play_statistics` row count == `play_state` row count. `favorites` count == `play_state WHERE is_favorite=true`.
- `users_without_tokens` — non-zero is OK only for users who never logged in.
- `orphan_albums` / `orphan_artists` — expect zero on a healthy library; non-zero indicates source-side dangling rows (acceptable, will be cleaned by scanner).
- `dangling_favorites` / `dangling_playlist_tracks` — **must be 0**. Non-zero = referential break, investigate before proceeding.
- `orphan_track_features` — **must be 0** for same reason.
- Embedding dimensions — discogs 1280, musicnn 200, clap 512, mert 768.

If any **must be 0** check is non-zero → **abort and rollback (Step 8)**.

### Step 5 — Smoke-test v2 via port-forward (no traffic yet)

Scale v2 up:

```bash
kubectl -n yay-tsa-v2-production scale deploy yay-tsa-v2-backend --replicas=1
kubectl -n yay-tsa-v2-production rollout status deploy/yay-tsa-v2-backend --timeout=180s
```

Port-forward and test:

```bash
kubectl -n yay-tsa-v2-production port-forward svc/yay-tsa-v2-backend 8096:8096 &
PF_PID=$!

# 1. Login (use your admin creds — note: v1 tokens are now invalid)
curl -sS -X POST http://localhost:8096/Users/authenticatebyname \
  -H 'Content-Type: application/json' \
  -H 'X-Emby-Authorization: MediaBrowser Client="cutover", Device="runbook", DeviceId="cutover-1", Version="1.0"' \
  -d '{"Username":"<ADMIN>","Pw":"<PASS>"}' | jq -r '.AccessToken' > /tmp/v2-token.txt

TOKEN=$(cat /tmp/v2-token.txt)
test -n "$TOKEN" || { echo "AUTH FAILED"; exit 1; }

# 2. /Items list (recursive, audio)
curl -sS "http://localhost:8096/Users/<USER_ID>/Items?Recursive=true&IncludeItemTypes=Audio&Limit=5" \
  -H "X-Emby-Token: $TOKEN" | jq '.TotalRecordCount, .Items[0].Name'

# 3. Stream a track (HEAD — verify 200 + Content-Type)
TRACK_ID=$(curl -sS "http://localhost:8096/Users/<USER_ID>/Items?Recursive=true&IncludeItemTypes=Audio&Limit=1" \
  -H "X-Emby-Token: $TOKEN" | jq -r '.Items[0].Id')
curl -sS -I "http://localhost:8096/Audio/$TRACK_ID/stream?api_key=$TOKEN" | head -10

# 4. Range request (seek)
curl -sS -I -H "Range: bytes=0-1023" "http://localhost:8096/Audio/$TRACK_ID/stream?api_key=$TOKEN" | head -5
# Expect: 206 Partial Content

kill $PF_PID
```

All four must succeed. If any fails → **rollback (Step 8)**.

### Step 6 — Flip ingress to v2

Edit the ingress backend. Pick the cleanest option that matches the actual manifest in `gitops`:

**Option A — edit Ingress backend service (recommended):**

```bash
# Inspect current ingress
kubectl -n yay-tsa-production get ingress -o yaml | grep -E 'host:|service:|name:' | head -20

# Patch the backend service name to point at the v2 service in v2 namespace.
# If the v1 ingress lives in yay-tsa-production and only supports same-namespace
# services, instead create the ingress in yay-tsa-v2-production and delete the
# v1 ingress.
```

Concrete: GitOps-managed ingress is the source of truth. Make the change in the gitops repo (`helm-charts/yay-tsa/`) — switch the values to v2 service and let Argo CD sync. For a faster manual flip during the cutover window:

```bash
# Manual override: delete v1 ingress, create v2 ingress
kubectl -n yay-tsa-production delete ingress yay-tsa-production-pwa
kubectl -n yay-tsa-v2-production apply -f /Users/nikolay/yay-tsa/yay-tsa-v2/k8s/ingress-cutover.yaml

# Then immediately sync the gitops repo to match (so Argo CD doesn't fight you).
```

**Option B — change Service selector** (only if v1 and v2 share a namespace, which they don't here — skip).

### Step 7 — Validate at public URL

```bash
# DNS / TLS still works
curl -sS -I https://yay-tsa.com/ | head -3

# PWA loads
curl -sS https://yay-tsa.com/ | grep -i '<title>'

# API responds (unauthenticated probe)
curl -sS -I https://yay-tsa.com/System/Info/Public | head -3
```

Then in the browser: open `https://yay-tsa.com`, login (fresh credentials — tokens were re-hashed), navigate to library, play a song, seek mid-track, mark a favorite. Cross-check the same on a phone if available.

Watch for 5 minutes:

```bash
kubectl -n yay-tsa-v2-production logs -f deploy/yay-tsa-v2-backend --tail=200
kubectl -n yay-tsa-v2-production get pods -w
```

### Rollback Triggers (from `docs/rollback-plan.md`)

Trigger **immediate** rollback if any of:

- Login fails for existing users (after they enter correct password).
- Library empty or missing >1% of tracks vs pre-flight count.
- Audio streaming returns 404/500 for known-good tracks.
- Play history visible but play counts are all zero.
- Favorites missing.
- Backend pod CrashLoopBackOff or readiness probe persistently failing.

### Step 8 — Rollback (if needed)

ETL is non-destructive — `public.*` is untouched. Rollback is just ingress + scale:

```bash
# 1. Flip ingress back to v1
kubectl -n yay-tsa-v2-production delete ingress yay-tsa-v2-pwa  # if you created one
# Restore v1 ingress from gitops (revert the gitops commit, or:)
kubectl -n yay-tsa-production apply -f /tmp/v1-ingress-backup.yaml

# 2. Scale v1 backend back up
V1_REPLICAS=$(cat /tmp/v1-replicas.txt)
kubectl -n yay-tsa-production scale deploy yay-tsa-production-backend --replicas=${V1_REPLICAS:-2}
kubectl -n yay-tsa-production rollout status deploy/yay-tsa-production-backend --timeout=180s

# 3. Scale v2 backend down (preserve for forensics)
kubectl -n yay-tsa-v2-production scale deploy yay-tsa-v2-backend --replicas=0

# 4. Optional: drop core_v2_* schemas (do NOT do this if you want to investigate)
# kubectl -n shared-database exec -i shared-postgres-1 -- psql -U postgres -d yaytsa_production -c \
#   "DROP SCHEMA core_v2_auth, core_v2_library, core_v2_playback, core_v2_playlists,
#                core_v2_preferences, core_v2_ml, core_v2_adaptive, core_v2_karaoke CASCADE;"

# 5. Verify v1 healthy
curl -sS https://yay-tsa.com/System/Info/Public
```

Restore DNS TTL to normal value after stable for 24h.

**Pre-cutover prep for fast rollback:** capture the current v1 ingress manifest:

```bash
kubectl -n yay-tsa-production get ingress -o yaml > /tmp/v1-ingress-backup.yaml
```

Do this **before** Step 6.

---

## Post-Cutover

### T+1h — Restore DNS TTL

Raise TTL back to normal (3600s or "Auto") in Cloudflare.

### T+24h — Reauth communication

Confirm all clients (your devices, family devices) are logged into v2. Old tokens are dead by SHA-256 hashing, so any client still trying to use a cached v1 token will get 401 — expected.

### T+7d — Decommission v1

If no rollback triggered and v2 has been clean for 7 days:

```bash
# Scale v1 backend to 0 (already done, just confirm)
kubectl -n yay-tsa-production scale deploy yay-tsa-production-backend --replicas=0

# Keep for 30 days; then delete the namespace entirely
# kubectl delete namespace yay-tsa-production
```

After 30 days clean, drop the legacy `public.*` schema:

```bash
# Take one final ad-hoc backup, then:
# DROP SCHEMA public CASCADE;  -- be VERY sure; this is irreversible
```

This is also when you reclaim disk on the CNPG cluster.

---

## Estimated Timings

| Phase                   | Duration   | Downtime?      |
| ----------------------- | ---------- | -------------- |
| Pre-flight (T-24h)      | 30 min     | no             |
| Ad-hoc backup           | 5–15 min   | no             |
| Freeze v1               | 30 s       | starts here    |
| ETL                     | 1–3 min    | yes            |
| Validation              | 30 s       | yes            |
| v2 smoke (port-forward) | 1 min      | yes            |
| Ingress flip + DNS      | 30 s       | ends here      |
| Public-URL validation   | 2 min      | no (v2 live)   |
| **Total user-visible**  | **<5 min** | **target met** |

Rollback path (if triggered) targets **<10 min** from decision to v1-back-on-public.

---

## References

- `scripts/etl-migrate.sql` — source of truth for what is migrated
- `scripts/validate-migration.sql` — post-ETL data integrity checks
- `docs/rollback-plan.md` — high-level rollback policy
- `docs/gap-analysis.md` — known coverage gaps
- Workspace `CLAUDE.md` § "ArgoCD Operations" — kubectl-only rule for Argo manipulations
