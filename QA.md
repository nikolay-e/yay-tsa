# QA — yay-tsa

Project-specific QA playbook for yay-tsa: a Kotlin/Spring Boot multi-protocol media server (Jellyfin + OpenSubsonic + MPD + MCP over one state engine) paired with a React PWA client. Generic methodology lives in the global `/qa` skill — this file holds only what is unique to yay-tsa.

## Applicability Matrix

| Capability                     | Applies | Notes                                                                |
| ------------------------------ | ------- | -------------------------------------------------------------------- |
| CI                             | ✅      | `ci.yml` (PWA + post-deploy-qa), `v2-ci.yml` (Kotlin backend build)  |
| CD / ArgoCD                    | ✅      | Image Updater write-back; two namespaces (see Coordinates)           |
| K8s logs                       | ✅      | Backend, frontend, audio-separator, feature-extractor CronJob        |
| Browser QA (Playwright)        | ✅      | Most click-heavy UI in the fleet — see `walkthrough.safe_click`      |
| Backend smoke                  | ✅      | Read + binary (Range/206) + write — never trust `/health` alone      |
| Tests                          | ✅      | Gradle (Testcontainers), `packages/core` integration, Playwright E2E |
| Diff-context review            | ✅      | `diffctx . --diff <from>..<to>`                                      |
| autoqa full                    | ✅      | crawler + axe + schemathesis + ZAP                                   |
| Schemathesis                   | ✅      | OpenAPI at `/api/v3/api-docs`, base-url `https://yay-tsa.com/api`    |
| ZAP                            | ✅      | Enabled in `post-deploy-qa`                                          |
| SonarCloud                     | ✅      | Automatic Analysis (see SonarCloud section)                          |
| Chat-review (LLM-authored msg) | N/A     | No user-facing bot/agent messages; LLM-DJ is a local stub            |
| Walkthrough Delta Pass         | ✅      | Long lists must be scrolled ≥30 items before screenshot              |

## Coordinates

- **Public URL**: `https://yay-tsa.com` (Subsonic at `/rest/*`, API at `/api/*`).
- **Namespaces**: `yay-tsa` (current prod — backend, frontend, audio-separator, feature-extractor pods all live here) plus legacy `yay-tsa-v2-production` references in some charts/scripts. CNPG cluster in `shared-database`.
- **OpenAPI**: springdoc default `/api/v3/api-docs` (NOT Jellyfin's legacy `/api-docs`). schemathesis `--url https://yay-tsa.com/api`.
- **SonarCloud key**: `nikolay-eremeev_yay-tsa`.
- **QA account**: username `test`; password in Keychain entry `yay-tsa-qa-password`. Subsonic `/rest/*` uses query-param auth (u/p/t), so a Bearer token 401s there — expected. NEVER use the `master` account (owner/admin; browser may autofill it) — admin login is forbidden during `/qa`.
- **DB schemas**: per-context `core_v2_*` (`auth`, `library`, `playback`, `adaptive`, `preferences`, `playlists`, `ml`, `karaoke`, `shared`) in `yaytsa_production`. `public` holds only extensions (pgvector, pg_trgm, citext, uuid-ossp).

## autoqa integration

Full pipeline: crawler + axe + schemathesis + ZAP, in the `post-deploy-qa` job of `ci.yml`. See `/qa` skill `Autoqa Logs` + `Crawler / autoqa` for log-reading and false-positive triage. yay-tsa-specific autoqa inputs already wired in CI: `schemathesis-base-url: https://yay-tsa.com/api` (Traefik strip-prefix means backend OpenAPI emits bare paths), and `schemathesis-exclude-paths` covering the binary image endpoint + SSE/event streams (`/v1/me/devices/events`, `/v1/me/devices/commands`, `/v1/groups/{id}/events`, `/v1/sessions/{sessionId}/queue/events`).

## walkthrough.safe_click

This is the most click-heavy UI in the fleet. The crawler clicks only the verbs below; everything else is left unclicked.

```yaml
walkthrough:
  safe_click:
    [
      Open,
      Expand,
      Show more,
      Filter,
      Sort,
      Play,
      Pause,
      Resume,
      Next,
      Previous,
      Seek,
      Shuffle,
      Repeat,
      Mute,
      Unmute,
      Favorite,
      Unfavorite,
      Queue,
      Lyrics,
      Karaoke,
      Search,
    ]
  # Destructive / state-mutating controls to AVOID (not on allowlist):
  #   Logout, Delete playlist, Remove from playlist, Clear queue,
  #   Library scan / rescan, Delete device, Leave group, Save preferences,
  #   any /Admin/* control, "Remove from favorites all", PWA uninstall.
  confirm_dialog_detected: fail # any unguarded confirm modal mid-walk fails the run
```

## Mandatory Browser-QA flows (project-specific positive signals)

Health endpoints, `/Items` HTTP 200, no-pod-restart, "the page renders" are liveness only — the audio-stream 500 shipped with all four green. Run on BOTH desktop and mobile (412×915, Pixel 7); each flow needs 0 console errors, 0 unexpected 4xx/5xx, and the named positive signal.

1. **Playback start (most critical)** — open album → Play first track. Within 3s: `<audio>` exists, `paused===false`, `currentTime>0`, `GET /api/Audio/<id>/stream` returns `206` (range) or `200`. Console must NOT contain `MEDIA_ELEMENT_ERROR` / `Failed to load audio` / `Audio engine error` (backend non-audio body the element can't decode).
2. **Seek** — `audio.currentTime = duration*0.5`; within 2s network shows fresh `Range: bytes=<offset>-` → 206. Repeated byte-0 request = range support broken.
3. **Next/previous** — Next fires new `/stream`, `currentTrack.Id` changes. Previous <3s in → previous track; >3s in → seeks to 0. Both required.
4. **Favorites** — heart → `POST /api/UserFavoriteItems/<id>` 200, icon fills, `/api/Items?Filters=IsFavorite` includes it; click again → DELETE 200.
5. **Karaoke** — toggle loads `/api/Karaoke/<id>/instrumental` 200; vocals toggle switches `/instrumental` ↔ `/vocals`.
6. **Devices / multi-device SSE** — `new EventSource('/api/v1/me/devices/events')` opens (readyState 1), receives `ready` within 5s; second tab appears within ~15s (heartbeat). SSE 500 = auth filter chain stripped the cookie (distinct from streaming 500).
7. **Radio similarity (ML actually fires)** — after clicking a Radio card, `fetch('/api/v1/sessions/{id}/queue').then(j => j.tracks.map(t => t.addedReason))` must contain at least one `bootstrap-similarity`. If none, the embedding path is dead (HNSW disabled, MERT column NULL, or controller bypassing `findSimilarTracks`). Audio-playing alone does NOT distinguish similarity-driven radio from generic top-affinity fallback.

For login/seek/auth-injection mechanics, mobile locator state-changes, PWA service-worker cache, Range-aware mocks, and the binary-path rule — see `/qa` skill `Browser QA`.

## Project-specific gotchas

### Multi-protocol path catalog

- **PWA → Jellyfin/Yaytsa**: `/Users/AuthenticateByName`, `/Sessions/Logout`, `/Items`, `/Items/{id}/Images/{type}`, `/Audio/{id}/stream`, `/Playlists`, `/UserFavoriteItems/{itemId}`, `/Sessions/Playing[/Progress|/Stopped]`, `/Admin/Users`, plus extensions `/v1/me/devices/*`, `/v1/sessions/*`, `/v1/users/{id}/preferences`, `/Karaoke/{trackId}/{status|instrumental|vocals}`.
- **Subsonic** lives at `/rest/*`, NOT `/api/rest/*` (class-level `@RequestMapping("/rest")`). Needs its own dedicated Ingress on the backend WITHOUT the strip-api middleware (`charts/yay-tsa-v2/templates/ingress-subsonic.yaml`, `ingress.subsonic.enabled`). Without it every `/rest/*` returns the PWA `index.html` (200 `text/html`) — Subsonic clients (Symfonium, Finamp, Feishin) appear "connected" but every endpoint serves the HTML shell. Each protocol path under the backend needs explicit Ingress allowlisting; add new ones (`/mpd-http`, `/.well-known/*`) to the chart, not as runtime workarounds.
- **MPD** enabled in-cluster only (`MPD_ENABLED=true`, ClusterIP `*-mpd:6600`, unauthenticated → no public ingress). Verify by grepping backend startup log for `MPD server listening on port 6600` — the `/dev/tcp` probe fails inside JRE-Alpine (busybox `sh` lacks it).
- **`/v1/*` extensions** test via the `/api/` prefix (e.g. `/api/v1/time`); the bare `https://yay-tsa.com/v1/*` hits frontend nginx and returns `index.html`. Traefik strip-prefix removes `/api` before the backend sees it.
- **`PlaylistItemId` is the playlist POSITION on the wire** (not a track id). The Move endpoint accepts position-first with track-id fallback; protocol clients (Subsonic/Jellyfin) call `POST /Playlists/{id}/Items/{PlaylistItemId}/Move/{n}` — the PWA never does.
- **Auth surfaces**: only `Authorization: Bearer <token>` survives the Cloudflare/Traefik proxy chain; `X-Emby-Token`, `X-Emby-Authorization`, and `?api_key=` all 401 through the public URL but succeed via `kubectl port-forward`. The PWA sends Bearer for `fetch()` and writes a `yay_token` cookie for `<audio src>` and `EventSource` (neither can set headers). When code authenticates via port-forward but fails via public URL → bug is in the proxy chain.

### Karaoke / audio-separator stem paths

- Stem path pattern: `/app/stems/{trackId}/{filename}_instrumental.wav` and `_vocals.wav`. The stems PVC must be mounted in BOTH the audio-separator AND backend deployments — separator writes, backend reads. If the backend can't see the files, `getStatus()` → `validateStemFilesExist()` resets `karaokeReady=false`.
- `tryRecoverFromOrphanedFiles()` can rebuild DB state from existing stems without re-processing. `karaoke_fail_count` column skips a track after 3 failures (no infinite backfill retries).
- Production uses the GPU sidecar (`services/audio-ml/`, BS-Roformer default); the in-process Demucs path is disabled via `DEMUCS_COMMAND=unsupported`. ML feature extraction is a CronJob on the essentia image (`extractor_cli.py`), NOT in-backend — keeps Python+torch+Essentia out of the JVM image.

### JVM heap = 75% of container limit → OOM kills streams

- The entrypoint computes JVM heap as 75% of the container memory limit, so raising the container limit automatically raises heap. When the backend OOMKills, ALL active stream connections break mid-playback — this is the "sometimes plays, sometimes doesn't" symptom.
- Concurrent `/Images/Primary` (50+) each decode a full image to `BufferedImage`, resize, convert to WebP — exhausts heap. Mitigations in place: `MAX_CONCURRENT_IMAGE_PROCESSING=4` semaphore + 3Gi backend limit. Audio-separator needs 6Gi for BS-Roformer (OOMKilled at 4Gi).
- Backend log `Unhandled exception` + `HttpMessageNotWritableException` is the unambiguous "5xx with body the client can't decode" signal. Grep before declaring a release healthy: `kubectl logs -n yay-tsa deployment/<backend> --since=5m | grep -E "Unhandled|MessageNotWritable|MessageConversion"`. (Async write-loop `AsyncRequestNotUsableException` on Pause/Skip is benign — swallowed by a no-body `@ExceptionHandler`.)

### Multi-device / SSE sync

- `useDeviceEvents` SSE hook must fetch the device list on mount — otherwise `updateDeviceState` maps over an empty array and drops every event. On an event for an unknown `deviceId`, refetch the full list.
- `broadcastDeviceState` must include `nowPlayingItemName` alongside `nowPlayingItemId` — otherwise DevicesPanel shows position but no track name.
- Group Sync SSE membership broadcast (`GroupEventBroadcaster`) is in-memory → single-replica only. Crawler timeout on SSE-heavy pages (`networkidle` never fires) is expected — those routes are in `schemathesis-exclude-paths`.

### Mobile E2E locator quirks

- Mobile viewport tests use a separate Playwright project (`[mobile]`, Pixel 7 412px).
- PlayerBar renders a mini-bar on mobile (`md:hidden`) and hides it via CSS `invisible` when the full player opens — `expect(playerBar.playerBar).toBeVisible()` FAILS while the full player is open; assert `playerBar.currentTrackTitle` instead. Test IDs must exist in BOTH the mini-bar AND MobileFullPlayer for `:visible` selectors. `ensureControlsVisible()` opens the full player and changes which elements are visible.
- `getByRole('button', { name: 'Shuffle' })` also matches the AlbumDetailPage text-button — use `locator('button[aria-label="Shuffle"][aria-pressed]:visible')` for player controls.
- Badge text may include a unit suffix ("15m") — parse with `Number.parseInt(text ?? '0', 10)`.
- `setPointerCapture` throws `DOMException` for synthetic pointer events — guard `handlePointerDown` with try/catch. Modal components must `createPortal` to escape invisible parents.
- Lighthouse LCP budget 3000ms (2500ms too tight for CI runners). Buttons with responsive text (`hidden sm:inline`) need explicit `aria-label` + `aria-hidden="true"` on the visible span.

### `core_v2_*` schema specifics

- **Cross-schema track-id refs are by-value, no FK**, in 11 tables (`favorites`, `play_history`, `play_statistics`, `queue_entries`, `playlist_tracks`, `track_features`, `user_track_affinity`, `karaoke.assets`, `adaptive.adaptive_queue`, `adaptive.listening_sessions`, `adaptive.playback_signals`). Any dedup/cleanup must remap all of them before dropping an `entities` row or favorites/history dangle.
- **Schema features ship dead.** ML embeddings, HNSW indexes, taste profiles, and `user_track_affinity` all existed in the DB and "looked deployed" while no production code read or wrote them for months. Treat every new schema feature as untrusted until a DB-backed integration test exercises a controller/worker that demonstrably reads/writes through it — the migration succeeding proves nothing will ever call it. Required end-to-end tests: embeddings drive bootstrap queue (≥50% overlap with `findSimilarTracks`), `PLAY_COMPLETE`/`SKIP_EARLY` signals move `affinity_score` within one worker tick, red-lines remove tagged tracks from recommendations, two distinct radio seeds yield non-identical sets, ArchUnit guards every injected `MlQueryPort` has a reachable `@Repository` impl. Operational signal: Micrometer `yaytsa.ml.affinity.last_updated_at` + Grafana alert on `>6h` stale.
- **Test-vs-prod column-type drift is a false-confidence trap.** Hibernate `ddl-auto` in Testcontainers creates `playback_signals.context` as **jsonb**; prod (Flyway/v1-ETL) is **text**. SQL using jsonb `->>` passes the integration test but floods prod with `BadSqlGrammarException` every ~60s. Fix that works on both: `substring(ps.context::text from '<regex>')`. A green integration test does NOT prove prod-safe SQL when schemas diverge — grep prod logs for `BadSqlGrammar` after any worker-SQL change.
- **`ddl-auto: validate` in prod** (own DDL via Flyway). `update` is a foot-gun: re-ALTERs columns on every boot, fails when rows exceed new bounds, races prepared-statement caches (`cached plan must not change result type`).
- **Placeholder vs missing tags.** Some FLAC rips store `##### ######` in TITLE/ARTIST/ALBUM (Cyrillic encode failure). `String.usableTag()` treats `^[#?\s]+$` as missing → folder/filename fallback. Filename-fallback titles strip leading `\d{1,3}\s*[-._]\s*`. Detection: `SELECT name FROM core_v2_library.entities WHERE entity_type IN ('ARTIST','ALBUM','TRACK') AND name ~ '^[#?\s]+$'`. If `ffprobe` shows the FLAC tag itself is the placeholder, code can only fall back — the cure is re-tagging the source. The mechanical `/qa` walkthrough checks (M1 mojibake, M2 tofu runs, M3 adjacent dups, M4 filename-in-title) catch this class — see `/qa` skill Phase 1.5.
- **Image rows rot** because the scanner walks audio files only and never inserts/repairs image rows — v1-ETL paths dangle whenever folders are renamed. Audiobook rips often carry art ONLY in tags: check `ffprobe -show_streams <file> | grep attached_pic` before treating an `/Images/Primary` 404 as a data bug. Fallback chain: image row → embedded art (item's own file, or first chapter for albums) → 404. An art-less album correctly 404s — the crawler flags it as a finding; gate-policy fix is upstream (autoqa), not app code.
- **TotalRecordCount on `/Items` must be a DB count, not `items.size`** — the PWA infinite-scroll stops at `loaded >= totalRecordCount`, so `items.size` caps a 9k-track library at "50 of 50". Sanity: `/api/Items?...&Limit=1 | jq .TotalRecordCount` must equal `SELECT count(*) ... entity_type='TRACK'`.

### springdoc / OpenAPI config

- **`OpenApiConfig.pathIdPatternCustomizer`** sets a `pattern` on every plain-string path param, declaring the real id alphabet so positive schemathesis fuzzing stops generating `;`-garbage that StrictHttpFirewall 400s. Biggest schemathesis FP-killer for this project.
- **`OpenApiConfig.globalResponsesCustomizer`** documents `application/problem+json` for 400/401/403/404/409/429/500 on every op. This is why every error body MUST be RFC7807 problem+json (filter-chain, controller, `HttpFailureTranslator`, and the custom `/error` `ErrorController` all converge on `dev.yaytsa.adaptershared.problemDetail(...)`). The PWA reads `errorData.message`, never `.error`.
- **`ResponseEntity<Map<String, Any>>`** makes springdoc emit `additionalProperties: {type: object}`; any array/scalar value in the real body violates it. Rule of thumb: any controller returning `Map<String, Any>` is a future schemathesis finding — return a typed DTO.
- **Binary streaming bypasses Spring's message-converter pipeline** — `/Audio/{id}/stream` takes `HttpServletResponse` and writes bytes directly. Any Kotlin `ResponseEntity<*>` (erases to `Any`) or unrecognized codec string for binary payloads silently fails `canWrite` → `HttpMessageNotWritableException` 500. Codec match is substring-based (`"FLAC 16 bits"` contains `flac`) with file-extension sniffing and `audio/mpeg` last-resort.

For the generic forms of these — Spring `ResponseEntity<String>` charset, `Map<String,Any>` schemathesis, StrictHttpFirewall 400, springdoc `http://` in `servers`, the filter-chain RFC7807 auth-entry-point, reverse-proxy base-url, binary/Range/206, and schema-shipped-but-unwired — see `/qa` skill `API conformance QA`, `Auth / session QA gotchas`, and the `Browser QA` binary bullet. Stale review artifacts (a `REVIEW_SECURITY.md` listing IDOR/MD5/ACTUATOR findings already remediated the same day): verify every stale review artifact against current HEAD before acting on it.

### SonarCloud (project-specific)

- Automatic Analysis (no `sonar-scanner` in CI) — `sonar-project.properties` is ignored; exclusions set via web UI/API.
- `yay-tsa-v2/` subtree must stay excluded — Kotlin rewrite with its own detekt config; mixing in adds ~167 Kotlin issues that mask real findings and force `new_security_rating=3`.

### Test-artifact hygiene

- Schemathesis/Hypothesis writes `.hypothesis/` in CWD — gitignore at workspace root AND in every dir pytest runs (`packages/core/`, `services/server/`).
- One-shot QA artifacts (`openapi.json`, `ANALYSIS.md`, `login-*.png`, `qa-*.{png,jpeg}`) accumulate at repo root — keep them gitignored.
- `kubectl cp` to CNPG pods fails (read-only fs) — pipe via stdin: `kubectl exec -i ... -- psql ... < script.sql`. Mutations must target the primary pod (`kubectl get cluster -n shared-database -o jsonpath='{.items[0].status.targetPrimary}'`); replicas reject as `read-only transaction`.

---

Generic patterns (CI continue-on-error/aggregate-gate, two-CI-cancel, ArgoCD Image-Updater write-back & disabled-service, kubectl connection-reset, Helm chart versioning, ZAP SQLi rate-limit FP, Sonar S3735/S7766, accessibility, pgbouncer idle-connection) live in the global `/qa` skill — do not duplicate here.
