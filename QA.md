# QA — yay-tsa

Project-specific QA playbook for yay-tsa: a Kotlin/Spring Boot multi-protocol media server (Jellyfin + OpenSubsonic + MPD + MCP over one state engine) paired with a React PWA client. Generic methodology lives in the global `/qa` skill — this file holds only what is unique to yay-tsa.

## Applicability Matrix

| Capability                     | Applies | Notes                                                                                                                            |
| ------------------------------ | ------- | -------------------------------------------------------------------------------------------------------------------------------- |
| CI                             | ✅      | `ci.yml` (PWA + post-deploy-qa), `v2-ci.yml` (Kotlin backend build)                                                              |
| CD / ArgoCD                    | ✅      | Image Updater write-back; two namespaces (see Coordinates)                                                                       |
| K8s logs                       | ✅      | Backend, frontend, audio-separator, feature-extractor CronJob                                                                    |
| Browser QA (Playwright)        | ✅      | Most click-heavy UI in the fleet — see `walkthrough.safe_click`                                                                  |
| Backend smoke                  | ✅      | Read + binary (Range/206) + write — never trust `/health` alone                                                                  |
| Tests                          | ✅      | Gradle (Testcontainers), `packages/core` integration, Playwright E2E                                                             |
| Diff-context review            | ✅      | `diffctx . --diff <from>..<to>`                                                                                                  |
| autoqa full                    | ✅      | crawler + axe + schemathesis + ZAP                                                                                               |
| Schemathesis                   | ✅      | OpenAPI at `/api/v3/api-docs`, base-url `https://yay-tsa.com/api`                                                                |
| ZAP                            | ✅      | Enabled in `post-deploy-qa`                                                                                                      |
| SonarCloud                     | ✅      | Automatic Analysis (see SonarCloud section)                                                                                      |
| Chat-review (LLM-authored msg) | N/A     | No user-facing bot/agent messages; LLM-DJ output is queue edits, not user-facing text (wired to LiteLLM, gated on `LLM_ENABLED`) |
| Walkthrough Delta Pass         | ✅      | Long lists must be scrolled ≥30 items before screenshot                                                                          |

## Coordinates

- **Public URL**: `https://yay-tsa.com` (Subsonic at `/rest/*`, API at `/api/*`).
- **Namespaces**: `yay-tsa` (current prod — backend, frontend, audio-separator, feature-extractor pods all live here) plus legacy `yay-tsa-v2-production` references in some charts/scripts. CNPG cluster in `shared-database`.
- **OpenAPI**: springdoc default `/api/v3/api-docs` (NOT Jellyfin's legacy `/api-docs`). schemathesis `--url https://yay-tsa.com/api`.
- **SonarCloud key**: `nikolay-e_yay-tsa` (org `nikolay-e`). The `qualitygates`/`issues` APIs 404 on any other key — verify with `api/components/search?organization=nikolay-e&q=yay-tsa`.
- **QA account**: username `test`; password in Keychain entry `yay-tsa-qa-password`. Subsonic `/rest/*` uses query-param auth (u/p/t), so a Bearer token 401s there — expected. NEVER use the `master` account (owner/admin; browser may autofill it) — admin login is forbidden during `/qa`.
- **DB schemas**: per-context `core_v2_*` (`auth`, `library`, `playback`, `adaptive`, `preferences`, `playlists`, `ml`, `karaoke`, `shared`) in `yaytsa_production`. `public` holds only extensions (pgvector, pg_trgm, citext, uuid-ossp).

## Repo topology correction (origin = Forgejo, GitHub = mirror)

Despite the project `CLAUDE.md` line claiming `origin` is `github.com:nikolay-e/yay-tsa`, the real `origin` is **Forgejo** (`git.nikolay-eremeev.com/nikolay-e/yay-tsa`) and GitHub is the push-mirror. Push fixes to `origin` (Forgejo); the mirror propagates to GitHub fast (CI on the GitHub side fired within ~seconds this pass). `gh` still works because it targets the GitHub mirror — issues, PRs, and `gh run` are all on GitHub. Dependabot PRs (`#242`, `#243` this pass) open + auto-merge on GitHub, land on GitHub `main`, and sync back to Forgejo — so a local `main` based on a pre-merge SHA shows up as "ahead N, behind M": rebase onto `origin/main` before pushing. Merging a dependabot PR via `gh pr merge` is correct here.

## Schemathesis residual is now an autoqa gate-policy fix (not app-fixable)

The chronic `post-deploy-qa` red — N× `[400] Undocumented Content-Type: text/html` on fuzzer-mutated paths containing C1 control chars (`%C2%92`, `%C2%82`) — is Tomcat's connector rejecting the URI **before** Spring's RFC7807 handler. Reproduce: `curl -X DELETE 'https://yay-tsa.com/api/Admin/Users/_V%3E%C2%92%23%5B%C2%82_'` → Tomcat HTML 400, while `.../foo%3Bbar` → `application/problem+json` (the StrictHttpFirewall/semicolon class is fixed). Unfixable in app code. As of **nikolay-e/autoqa@57bd544** the gate classifies a schemathesis failure whose sole finding is `text/html` content-type on a **4xx** as informational (not blocking); 5xx text/html still blocks, and an unreconciled parse falls back to failing the full count. So this class should no longer gate `main` — if post-deploy-qa is red again, confirm it's NOT this class before triaging. Don't add these endpoints to `schemathesis-exclude-paths` (kills real positive coverage).

## autoqa integration

Full pipeline: crawler + axe + schemathesis + ZAP, in the `post-deploy-qa` job of `ci.yml`. **Budget `timeout-minutes: 120`** — schemathesis `--checks all` runs three phases (Coverage ~8min + Fuzzing ~30min + Stateful ~25min) sequentially BEFORE crawler/axe/ZAP, and the chronic failing endpoints make Hypothesis shrink for a long time; at 60min the job was SIGTERM'd (`exit 143`) mid-Stateful and ZAP/crawler never ran. The autoqa step runs schemathesis first, so a truncated job means NO ZAP/crawler/axe results — check the job ran to a `Complete job` line, not just that the summary counts look plausible. See `/qa` skill `Autoqa Logs` + `Crawler / autoqa` for log-reading and false-positive triage. yay-tsa-specific autoqa inputs already wired in CI: `schemathesis-base-url: https://yay-tsa.com/api` (Traefik strip-prefix means backend OpenAPI emits bare paths), and `schemathesis-exclude-paths` covering the binary image endpoint + SSE/event streams (`/v1/me/devices/events`, `/v1/me/devices/commands`, `/v1/groups/{groupId}/events`, `/Karaoke/{trackId}/status/stream`). The brace name MUST match the springdoc path template exactly — schemathesis `--exclude-path` is a literal template match, so `/v1/groups/{id}/events` excludes nothing (real param is `{groupId}`) and the SSE endpoint gets fuzzed into false conformance failures.

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
7. **Radio similarity (ML actually fires)** — start a session, `POST /api/v1/sessions/{id}/queue/refresh`, then `fetch('/api/v1/sessions/{id}/queue').then(j => j.tracks.map(t => t.addedReason))` must contain at least one `bootstrap-similarity`. If none, the embedding path is dead (HNSW disabled, MERT column NULL, or controller bypassing `findSimilarTracks`). Audio-playing alone does NOT distinguish similarity-driven radio from generic top-affinity fallback. **Before declaring the path dead, confirm the seed actually registered.** The `POST /api/v1/sessions` body field is snake_case `seed_track_id` with NO PascalCase/camelCase alias (`StartSessionRequest.seed_track_id`) — sending `SeedTrackId`/`seedTrackId` parses to null, the session has no seed, and `findSimilarTracks` is never called → queue is 100% `bootstrap-affinity` (a TEST ARTIFACT, not a bug). A correct seed yields `seed-track: 1` + `bootstrap-similarity: N`. A seed whose own track has no embedding (e.g. a 20-min full-album FLAC the ML extractor skipped) correctly falls back to `seed-track: 1` + `bootstrap-affinity: N` — graceful, also not a bug. Test ≥3 distinct real seeds before concluding; one embedding-less seed proves nothing.

For login/seek/auth-injection mechanics, mobile locator state-changes, PWA service-worker cache, Range-aware mocks, and the binary-path rule — see `/qa` skill `Browser QA`.

## Project-specific gotchas

### Multi-protocol path catalog

- **PWA → Jellyfin/Yaytsa**: `/Users/AuthenticateByName`, `/Sessions/Logout`, `/Items`, `/Items/{id}/Images/{type}`, `/Audio/{id}/stream`, `/Playlists`, `/UserFavoriteItems/{itemId}`, `/Sessions/Playing[/Progress|/Stopped]`, `/Admin/Users`, plus extensions `/v1/me/devices/*`, `/v1/sessions/*`, `/v1/users/{id}/preferences`, `/Karaoke/{trackId}/{status|instrumental|vocals}`.
- **Subsonic** lives at `/rest/*`, NOT `/api/rest/*` (class-level `@RequestMapping("/rest")`). **The real edge on `yay-tsa.com` is the frontend nginx, not the dedicated Traefik subsonic ingress.** Traefik routes the whole host to the frontend `yay-tsa-production` ingress (its length-based default priority beats the subsonic ingress's explicit `router.priority: "20"`), so `/api` works only because nginx proxies it (`location /api/`) and `/rest/*` returns the PWA `index.html` (200 `text/html`) unless nginx ALSO proxies it. Fix = add `location /rest/` (proxy to backend, NO `/api` strip, keep Range for `/rest/stream.view`) in `infra/nginx/nginx.conf.template`, mirroring the `/api/` block. Verify: `curl 'https://yay-tsa.com/rest/ping.view?u=test&p=<pw>&f=json'` must return `{"subsonic-response":{"status":"ok"...}}`, not HTML; `cf-cache-status: DYNAMIC` confirms it's origin (not CF cache). The backend itself is fine — port-forward `svc/yay-tsa-v2-production-backend:80` and hit `/rest/ping.view` to isolate routing from the adapter. schemathesis tests only `/api`, so it never catches a broken `/rest`.
- **MPD** enabled in-cluster only (`MPD_ENABLED=true`, ClusterIP `*-mpd:6600`, unauthenticated → no public ingress). Verify by grepping backend startup log for `MPD server listening on port 6600` — the `/dev/tcp` probe fails inside JRE-Alpine (busybox `sh` lacks it).
- **`/v1/*` extensions** test via the `/api/` prefix (e.g. `/api/v1/time`); the bare `https://yay-tsa.com/v1/*` hits frontend nginx and returns `index.html`. Traefik strip-prefix removes `/api` before the backend sees it.
- **`PlaylistItemId` is the playlist POSITION on the wire** (not a track id). The Move endpoint accepts position-first with track-id fallback; protocol clients (Subsonic/Jellyfin) call `POST /Playlists/{id}/Items/{PlaylistItemId}/Move/{n}` — the PWA never does.
- **Auth surfaces**: only `Authorization: Bearer <token>` survives the Cloudflare/Traefik proxy chain; `X-Emby-Token`, `X-Emby-Authorization`, and `?api_key=` all 401 through the public URL but succeed via `kubectl port-forward`. The PWA sends Bearer for `fetch()` and writes a `yay_token` cookie for `<audio src>` and `EventSource` (neither can set headers). When code authenticates via port-forward but fails via public URL → bug is in the proxy chain.

### Karaoke / audio-separator stem paths

- Stem path pattern: `/app/stems/{trackId}/{filename}_instrumental.wav` and `_vocals.wav`. The stems PVC must be mounted in BOTH the audio-separator AND backend deployments — separator writes, backend reads. If the backend can't see the files, `getStatus()` → `validateStemFilesExist()` resets `karaokeReady=false`.
- `tryRecoverFromOrphanedFiles()` can rebuild DB state from existing stems without re-processing. `karaoke_fail_count` column skips a track after 3 failures (no infinite backfill retries).
- Production uses the GPU sidecar (`services/audio-ml/`, BS-Roformer default); the in-process Demucs path is disabled via `DEMUCS_COMMAND=unsupported`. ML feature extraction is a CronJob on the essentia image (`extractor_cli.py`), NOT in-backend — keeps Python+torch+Essentia out of the JVM image.
- **Manually-pinned CronJob images can ship a broken image silently.** `feature-extractor`/`lyrics-sync`/`taste-clusters` CronJobs share one `services/audio-ml` image, pinned by hand in gitops `values.images.yaml` (Image Updater can't track CronJobs — no live pod to inspect). The three CLIs `import db` (shared `db.py`); if the `Dockerfile` `COPY` list omits a shared module (`db.py`), every CronJob crashes at import (`ModuleNotFoundError: No module named 'db'`) and ML/lyrics/clusters die silently — `/Items` and playback stay green. **Detect every pass:** `kubectl get pods -n yay-tsa` for CronJob pods in `Error`; the audio-separator (`uvicorn app:app`, no `import db`) survives, masking it. **Fix:** add the `COPY` in the Dockerfile, push (rebuilds the image), bump the gitops pin to the new SHA. **Verify the fixed image end-to-end without waiting for the daily schedule:** `kubectl create job -n yay-tsa qa-verify --from=cronjob/yay-tsa-production-taste-clusters` (lightest, no external deps) → pod must reach `Completed` and log real work, not crash at import. Delete the verify job after.
- **ArgoCD app health can wedge `Degraded` after a CronJob job-pod failure** even once the image is fixed and all Deployments/pods are `Healthy` and a fresh job `Completed`: `health.lastTransitionTime` stays frozen at the crash time and does NOT clear via `refresh=hard`, a manual sync, or a successful job. No per-resource health shows `Degraded` (all empty), and there's no custom CronJob health in `argocd-cm` — it's a stale computed field. It clears on the next real resource transition (e.g. the next frontend image roll). Do NOT restart the `argocd` application-controller during `/qa` (admin namespace, forbidden). Trust the live signals (site 200, playback, deployment `Available=True`) over the app's stale `Degraded`.

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
- **No-content endpoints (`204`) are a schemathesis "Undocumented HTTP status code" trap.** A handler returning `ResponseEntity<Void>`/`Unit` answers `204`, but springdoc only infers `200`, so a real `204` (e.g. `POST /v1/client-errors`) trips the gate. Declare `204` in `OpenApiConfig` via an `OperationCustomizer` that inspects `handlerMethod` return type (`noContentResponseCustomizer`) — NOT a swagger `@ApiResponse` annotation on the controller: the thin adapters (adapter-jellyfin) have no `springdoc`/`swagger-annotations` on their classpath, so the import is `Unresolved reference 'swagger'` in a clean `:adapter-jellyfin:compileKotlin` (it can pass on a warm local daemon — only the clean CI build is authoritative). All OpenAPI shaping lives in the `app` module's `OpenApiConfig`.
- **When the autoqa gate reports "schemathesis reported N failure(s)" with N small, find the odd-one-out.** The gate reconciles the chronic `text/html` 4xx (Tomcat/nginx pre-servlet rejections) to informational, so a residual `N=1` blocking is usually a REAL conformance bug hiding among dozens of reconciled control-char 400s — read every `FAILURES` block, not just the first. The `204`-undocumented client-errors case surfaced exactly this way.
- **Binary streaming bypasses Spring's message-converter pipeline** — `/Audio/{id}/stream` takes `HttpServletResponse` and writes bytes directly. Any Kotlin `ResponseEntity<*>` (erases to `Any`) or unrecognized codec string for binary payloads silently fails `canWrite` → `HttpMessageNotWritableException` 500. Codec match is substring-based (`"FLAC 16 bits"` contains `flac`) with file-extension sniffing and `audio/mpeg` last-resort.
- **Wrong `Content-Type` on a JSON `@RequestBody` endpoint must map to 415, not 500 — and schemathesis cannot catch this.** Schemathesis fuzzes bodies but sends the OpenAPI-declared `application/json`, so it never exercises a mismatched content-type; only a manual `curl -H 'Content-Type: text/plain' --data-binary $'\x00'` (or `application/x-www-form-urlencoded`, or no CT) does. Spring throws `HttpMediaTypeNotSupportedException`; if `GlobalExceptionHandler` lacks a handler for it the request falls through to `handleGeneric` → **500 + `log.error("Unhandled exception")` on every malformed client request** (5xx lying about a 4xx, plus error-log spam that masks real errors). Fix = explicit `@ExceptionHandler(HttpMediaTypeNotSupportedException)` → 415 (and `HttpMediaTypeNotAcceptableException` → 406), with 415/406 added to `OpenApiConfig.STANDARD_ERROR_RESPONSES`. Detect every pass: `kubectl logs deploy/yay-tsa-v2-production-backend --since=1h | grep -c "HttpMediaTypeNotSupported"` should be 0; if a `/qa` content-type fuzz produces a 500, it's this gap. (Found + fixed 2026-06-21; issue #252's CF-520 was a separate rollout-transient, but the investigation surfaced this real 500-vs-415 gap.)

For the generic forms of these — Spring `ResponseEntity<String>` charset, `Map<String,Any>` schemathesis, StrictHttpFirewall 400, springdoc `http://` in `servers`, the filter-chain RFC7807 auth-entry-point, reverse-proxy base-url, binary/Range/206, and schema-shipped-but-unwired — see `/qa` skill `API conformance QA`, `Auth / session QA gotchas`, and the `Browser QA` binary bullet. Stale review artifacts (a `REVIEW_SECURITY.md` listing IDOR/MD5/ACTUATOR findings already remediated the same day): verify every stale review artifact against current HEAD before acting on it.

### SonarCloud (project-specific)

- Automatic Analysis (no `sonar-scanner` in CI) — `sonar-project.properties` is ignored; exclusions set via web UI/API.
- `yay-tsa-v2/` subtree must stay excluded — Kotlin rewrite with its own detekt config; mixing in adds ~167 Kotlin issues that mask real findings and force `new_security_rating=3`.
- **S3735 ("remove `void` operator") is a standing conflict** with the enforced `@typescript-eslint/no-floating-promises` (`eslint.config.js`): the codebase uses `void promise()` deliberately for fire-and-forget, and removing it fails ESLint + reintroduces floating-promise bugs. Don't "fix" these — they're bulk-accepted in Sonar. New `void` calls in future code will re-raise S3735; accept, don't rewrite.
- **S7766 false positive** on `useAudiobooks` `reduce` over `resume.updatedAt`: those are ISO-timestamp STRINGS, so `Math.max` would coerce to `NaN`; the string-comparison ternary is correct (marked FP).

### Test-artifact hygiene

- Schemathesis/Hypothesis writes `.hypothesis/` in CWD — gitignore at workspace root AND in every dir pytest runs (`packages/core/`, `services/server/`).
- One-shot QA artifacts (`openapi.json`, `ANALYSIS.md`, `login-*.png`, `qa-*.{png,jpeg}`) accumulate at repo root — keep them gitignored.
- `kubectl cp` to CNPG pods fails (read-only fs) — pipe via stdin: `kubectl exec -i ... -- psql ... < script.sql`. Mutations must target the primary pod (`kubectl get cluster -n shared-database -o jsonpath='{.items[0].status.targetPrimary}'`); replicas reject as `read-only transaction`.

---

Generic patterns (CI continue-on-error/aggregate-gate, two-CI-cancel, ArgoCD Image-Updater write-back & disabled-service, kubectl connection-reset, Helm chart versioning, ZAP SQLi rate-limit FP, Sonar S3735/S7766, accessibility, pgbouncer idle-connection) live in the global `/qa` skill — do not duplicate here.

## post-deploy-qa must wait for the LAST BACKEND-BUILT sha, not the current sha

`post-deploy-qa` (in `ci.yml`, runs on **every** push to main) shares the `wait-backend-rollout` composite with `v2-ci.yml`. The action polls `/api/System/Info/Public` `BuildSha` for `main-<sha7>`. In `v2-ci` that's correct (it only runs on backend-path changes, so the current sha IS the new backend tag). In `ci.yml` it is NOT: the backend image is rebuilt only when `v2-ci.yml`'s path filter matches (`yay-tsa-v2/**`, `charts/yay-tsa-v2/**`, the v2-ci workflow), so a **frontend- or docs-only push never produces a `main-<this-sha>` backend image** and the wait times out forever — every non-backend commit's post-deploy-qa went red on "Backend never settled". Fix (shipped): `ci.yml` resolves the sha of the last backend-touching commit (`git log -1 -- yay-tsa-v2 charts/yay-tsa-v2 .github/workflows/v2-ci.yml`, needs `fetch-depth: 0`) and passes it to the action via the new `commit-sha` input. When triaging a red post-deploy-qa, first confirm it's not this class (only relevant if the wait step itself fails).

## Helm chart releases silently dropped — Forgejo push-mirror clobbers GitHub gh-pages

The chart repo is GitHub Pages `gh-pages`, authored ONLY by GitHub Actions (`release-chart.yml`). But the Forgejo→GitHub **push-mirror** mirrors all refs, including `gh-pages`, and the reverse-sync cron (`gitops:.../cronworkflow-github-reverse-sync.yaml`) only synced `main` + bot branches back GitHub→Forgejo — `gh-pages` fell through untouched. Net: every chart release pushed to GitHub's gh-pages was reverted to Forgejo's stale copy on the next mirror cycle, freezing the published chart (and silently dropping all chart changes). ArgoCD shows `Synced`+`Healthy` because it faithfully deploys the newest-in-range published chart — which is just stale. **Detection:** compare `curl -s https://nikolay-e.github.io/yay-tsa/index.yaml` newest `yay-tsa-v2` version + `created` date against recent chart commits; or `gh api 'repos/nikolay-e/yay-tsa/commits?sha=gh-pages'` HEAD date. If the live deployment lacks a chart change that's merged to main (e.g. a configmap/deployment env that the chart template clearly renders), suspect this, not the chart template. Fix (shipped, gitops): add `gh-pages` to the reverse-sync force-sync case so GitHub stays authoritative for it.

## A "live config lacks a chart key" symptom usually means stale chart, not a chart bug

When a `kubectl get configmap/deployment` shows the live render missing a key the **current** chart template renders (e.g. backend env `LLM_API_KEY`, configmap `LLM_BASE_URL`), the chart is almost always fine — the cluster is running an older published chart. Confirm by diffing the live configmap/deployment env against `helm template charts/<chart>` locally; if they differ, it's a publish/sync gap (see the gh-pages section above), not a template fix. The recurring `LlmClient: LLM enabled but no API key configured` WARN every 30s is the canonical example: secret `LLM_API_KEY` exists, chart template injects it under `llm.enabled`, but the deployed (old) chart had no such env block.

## `:app:test` flake lottery has TWO distinct shared-context root causes (a different test fails each run)

The `:app:test` suite runs every integration class in ONE Spring context against ONE Postgres, committing seed rows via `JdbcTemplate` (no per-test rollback). Two independent races make it fail non-deterministically — a _different_ class fails each run, which masks that these are systemic, not per-test, bugs. Both fixed 2026-06-21; if `:app:test` goes red intermittently, check it's not a regression of either before chasing the named test:

1. **Auth rate-limit bucket sharing.** `/Users/AuthenticateByName` is rate-limited per client IP (`remoteAddr` + URI). All MockMvc requests default to one `remoteAddr`, so logins across every class share ONE bucket and 429 intermittently — surfacing as a login `assertEquals(200)` failing deep inside a helper (e.g. `SubsonicApiIntegrationTest.issueApiToken` → cascades to whatever test called it). Fix: a shared `uniqueClientIp()` `RequestPostProcessor` in `HttpIntegrationTestBase` stamps a distinct synthetic IP per login; every token-issuing login (`AuthIntegrationTest`, `SubsonicApiIntegrationTest`) must `.with(uniqueClientIp())`. A GET-with-empty-creds 401 check is not a token-issuing login and doesn't need it.
2. **Scheduled library scan reconciling against the empty test music-path.** `LibraryScanner.scan()` is `@Scheduled(initialDelay=5_000)` and fires on a background thread ~5s into the JVM, walking `yaytsa.library.music-path` (set to the empty `tmpdir` in the base) and deleting orphan artist/album `entities` — including rows a test seeded microseconds earlier (track inserted last, so artist/album look orphaned), throwing `albums_artist_id_fkey` FK violations mid-`@BeforeEach`. `ItemsEndpointBenchmark` already carried a band-aid guard against exactly this. Fix: gate the scheduled run behind `yaytsa.scanner.scheduled-enabled` (default `true` for prod, set `false` in `HttpIntegrationTestBase`); the manual `LibraryScanTriggerPort` and direct `LibraryScanner(writer, root).scan()` constructions (scanner-module hygiene tests) keep working via the Kotlin `= true` default. Don't "fix" by pointing music-path at a missing dir (changes streaming/scan-trigger tests) or disabling all scheduling.

## Testcontainers reused-PG: serialize CREATE EXTENSION (pg_extension_name_index race)

`SharedPostgresContainer` uses `withReuse(true)`, so the parallel per-module Gradle test JVMs share ONE physical Postgres. `CREATE EXTENSION IF NOT EXISTS` is NOT concurrency-safe — two JVMs can both pass the existence check and both INSERT, one failing with `duplicate key value violates unique constraint "pg_extension_name_index"`. It surfaces as a context-load `IllegalStateException` cascading to every test in one class (often `AdaptiveQueryPortTest`), intermittently, on commits that don't even touch Kotlin. Fix (shipped): wrap the extension creation in `SharedPostgresContainer` in a session advisory lock (`pg_advisory_lock`) and pre-create every extension any module's Flyway needs (citext, vector, pg_trgm, unaccent) so downstream `IF NOT EXISTS` calls are guaranteed no-ops. A single green re-run does NOT disprove this class (it's a race); reason about the lock, don't trust one pass. Don't "fix" it by disabling parallelism or reuse.

## Backend-rollout 502s in post-deploy-qa (when a /qa pass ships backend changes)

`post-deploy-qa` now waits for the backend (last-built sha, see above) before fuzzing, so mid-rollout 502 bursts should be rare. If they still appear: When the same pass also changes the Kotlin backend (new image → legitimate rollout), the schemathesis phase can land mid-rollout and report a burst of **`502 Bad Gateway` (Cloudflare HTML)** as "Server error: N" — plus derived "JSON deserialization error" / "Undocumented Content-Type/HTTP status" from the 502 HTML body. These are transient deploy artifacts, NOT app bugs; verify by reading the 502 body (`yay-tsa.com | 502: Bad gateway`) and by confirming crawler/axe (frontend) stayed at 0 violations and ZAP HIGH = 0. The authoritative clean schemathesis run is the next post-deploy-qa that lands entirely **after** `kubectl rollout status deploy/yay-tsa-v2-production-backend` reports complete and the new pod is `1/1 Running` with a settled age. Don't file the 502s.

## Metadata enricher (MusicBrainz + Cover Art Archive)

`infra-metadata-enricher` is a `@Scheduled`+`@ConditionalOnProperty(yaytsa.metadata.enabled)` worker bean in the single backend bootJar. It writes `artists/albums.musicbrainz_id` (+ `release_group_mbid`) and drops `cover.jpg` into each album's own folder. Verify it in prod: `kubectl logs deploy/yay-tsa-v2-production-backend -n yay-tsa --since=10m | grep MetadataEnricher` shows `cycle starting`/`cycle complete: N artists, N albums`; DB progress `SELECT count(*) FROM core_v2_library.albums WHERE release_group_mbid IS NOT NULL` on the CNPG primary.

- **Writes need the media mount writable.** The cover lands under `music-path` (so the existing `/Items/{id}/Images` serves it with no code change), but the `media` volume is `readOnly: true` by default in the chart. Prod sets `backend.media.readOnly: false`. With a read-only mount the enricher silently can't write art (MBIDs still set).
- **External-provider clients MUST throw on transient failure, never swallow as empty.** Idempotency uses `metadata_checked_at` (set after every attempt so no-match never re-queries). If the MusicBrainz/CAA client returns an empty list on a timeout/5xx/429 (indistinguishable from a genuine 404/no-match), the enricher marks the row checked and the album is **permanently** skipped — a whole cluster-outage cycle silently burns every row it touched. Correct contract: client throws `MetadataProviderUnavailableException` on network error / exhausted-retry / 429/503/5xx, returns null only on 404. The per-item `try/catch` then leaves `metadata_checked_at` unset → retried next cycle. Recovery for already-burned rows: `UPDATE core_v2_library.albums SET metadata_checked_at = NULL WHERE metadata_checked_at IS NOT NULL AND musicbrainz_id IS NULL AND release_group_mbid IS NULL;` (and artists on `musicbrainz_id IS NULL`) on the primary — re-queues only un-enriched rows.
- **Egress check from the pod**: `kubectl exec <pod> -- wget -T12 -qO- 'https://musicbrainz.org/ws/2/artist?query=artist:Radiohead&fmt=json&limit=1'` returns **403** (busybox sends no User-Agent; MB blocks blank UAs) — that 403 still proves DNS + connectivity work. The real client sends the mandatory UA. CAA returns 307 (redirect to archive.org). A `connect timed out` in enricher logs right after a cluster outage is residual node-DNS flakiness (CoreDNS restarting), not an egress block.

## Local composite action needs `actions/checkout` in its job

Both post-deploy jobs (`post-deploy-qa` in `ci.yml`, `post-deploy-verify` in `v2-ci.yml`) call `uses: ./.github/actions/wait-backend-rollout`. A **local** action only exists on the runner after `actions/checkout` runs in that same job — without it the step fails setup with `Can't find 'action.yml'… Did you forget to run actions/checkout before running your local action?`, which then cascades (e.g. `ZAP report not generated` because AutoQA never ran). When extracting an inline `run:` block into a shared `.github/actions/*` composite, add a `Checkout repository` step to **every** job that now `uses:` it — the post-deploy jobs historically had no checkout because they only ran inline scripts and remote actions.

## LLM-DJ routes through the in-cluster LiteLLM gateway (GPT-5.4 Mini)

`infra-llm` `LlmClient` calls the OpenAI-compatible `/v1/chat/completions` on `http://litellm.litellm.svc.cluster.local:8080` (Bearer `LLM_API_KEY`, model `GPT-5.4 Mini`), not the Anthropic API. Verify go-live: `kubectl logs deploy/yay-tsa-v2-production-backend -n yay-tsa --since=5m | grep LlmOrchestrator` shows `LLM-DJ added N tracks to session …` (the end-to-end proof the gateway call + OpenAI-format parse + queue-edit apply all work). Three wiring dependencies, all in gitops: (1) the backend's namespace (`yay-tsa`) must be in litellm's `gatewayClientNamespaces` (else its NetworkPolicy blocks ingress — symptom: `Connection refused` to `litellm:8080`); (2) `LLM_API_KEY` (a dedicated LiteLLM virtual key, SOPS-encrypted) in `yay-tsa-production-secrets`; (3) `values.prod` `backend.config.llm.enabled: true`. The key has a hard $/30d budget so an exhausted key 429s → `LlmClient` falls back to ML-only, never crashes.

- **ksops secret edits can show a false ArgoCD `Synced`.** After `sops set` adds a key, the `secrets-yay-tsa` app may report `Synced` while the live Secret still lacks it (its last real sync op was weeks old; the refresh comparison didn't re-apply). Force it: `kubectl patch application secrets-yay-tsa -n argocd --type merge -p '{"operation":{"sync":{"revision":"HEAD","syncStrategy":{"apply":{"force":true}}}}}'`. If a deployment references a secret key mid-rollout before the secret syncs, the pod fails container-create with `couldn't find key LLM_API_KEY in Secret …` and recovers once the key lands.
- **`encrypt-data` hotspot on the in-cluster `http://litellm…` URL** (chart configmap/values) is a SonarCloud FP → mark `REVIEWED/SAFE`: cluster-internal ClusterIP traffic confined by NetworkPolicy, TLS not applicable.

## Audiobook exclusion is layered (genre `Audiobook`)

Three independent filters, by surface: (1) main library `/Items` — backend `ExcludeGenres` param (count-correct, drops `TotalRecordCount`; the PWA sends it on `useInfiniteTracks`); (2) daily-mix + radio-seeds — backend adaptive filter (`isAudiobookTrack` in `JellyfinAdaptiveController`), because the `RadioSeed` DTO carries no genre so the client cannot filter it; (3) daily-mix also has a client `!isAudiobook` belt-and-suspenders in `DailyMix.tsx`. If audiobooks reappear in radio/daily-mix it's the **backend** (check it's deployed); if in the main library list with a wrong count, it's the `ExcludeGenres` query. Favourites filter uses `IsFavorite=true` (the Jellyfin `Filters=IsFavorite` form is ignored and returns the full library — don't use it to count favourites).

## LLM-DJ over-spend check (the `@Scheduled` orchestrator is a paid side-effect)

`LlmOrchestrator.processActiveSessions` runs every 30s over every un-ended adaptive session and calls the (billed) LiteLLM completion. **A correct run is near-silent.** Smoke it by the call rate, not by "it works": `kubectl logs deploy/yay-tsa-v2-production-backend -n yay-tsa --since=2m | grep -c 'LLM-DJ added'` should be roughly the number of sessions that saw _new_ listening signals in that window — single digits, not ~30. A steady ~15/min means the freshness gate regressed (the orchestrator must skip a session when its newest signal id equals the last decision's `triggerSignalId`; without that gate every active session is re-billed every tick forever). The dedicated LiteLLM key has a hard $/30d budget so the worst case degrades to ML-only (graceful), but a high steady rate is a real cost bug to fix at the gate, never by disabling the DJ. Token-per-decision audit columns (`llm_decision.prompt_tokens/completion_tokens`) are currently unpopulated — don't rely on them for cost; count log lines or read the LiteLLM key's spend.

## Run the FULL module test suite before pushing a domain-logic fix

A domain fix can be correct yet break an **existing** test that encoded the old (buggy) behavior — and the `core-domain:playback` suite runs in `./gradlew build`, so CI catches it, but only after a ~15min build + the Testcontainers flake lottery. Example: the `play()`-resume position fix (snapshot `computePosition` instead of keeping the stale `lastKnownPosition`) was correct, but `PlaybackHandlerTest "Play with current entryId preserves position"` asserted the rewound `42s` (the bug) instead of the true `47s`. Compiling the changed module + running only the _new_ test is NOT enough — run the changed module's whole `:test` task (`./gradlew :core-domain:playback:test`) locally before pushing. When an existing test fails on a domain fix, first decide whether it encodes the bug (update the assertion to the correct value, with a comment) vs. a real regression (revert) — never blindly flip it green.

## Client-error telemetry review (every /qa pass)

The PWA ships sanitized client-side error reports to `POST /v1/client-errors` (unauthenticated, bucket4j 30/min/IP). Backend writes one compact JSON line via the dedicated `client-error` SLF4J logger (→ stdout → Promtail → Loki) and increments `yaytsa_client_errors_total{category,type,version}` (+ `yaytsa_client_errors_dropped_total{reason}`). This is the ONLY window into client-only failures that never reach the backend (render crashes, offline DECODE errors, CSP violations). **Reviewing it is the point of the feature — read the captured errors and fix the real bugs, don't just confirm the pipe is alive.**

Coordinates: namespace `yay-tsa`, deployment `yay-tsa-v2-production-backend`, logger name `client-error`, public URL `https://yay-tsa.com/api/v1/client-errors` (nginx/Traefik strip `/api` → backend `/v1/client-errors`).

**`kubectl logs` is NOT the source of truth — it truncates at the current pod's start.** A backend rollout (every `/qa` that ships a backend change, or any Image-Updater bump) restarts the pod, so `kubectl logs --since=24h` only shows minutes of history and will read as "almost no client errors". The authoritative all-time view survives restarts in two places: **Loki** (`{namespace="yay-tsa"} |= "client_error"` over 7–30d — full message/`fp`/`category`/`type`/`version` content) and the **Prometheus counter** via `sum by (category,type,version) (increase(yaytsa_client_errors_total[30d]))` (the in-pod `yaytsa_client_errors_total` instant value resets on restart; `increase()` reconciles resets). Port-forward `svc/prometheus-prometheus:9090` and `svc/loki:3100` in `monitoring` (read-only, fine for `/qa`). Triage every distinct `fp` against the deployed **frontend** `version` tag (the tag IS the frontend app version): a class that appears only on versions OLDER than a fix is already-fixed — confirm by checking the current frontend version's counter is 0 (browse a page that would trigger it, then re-read). The recurring `resource/ResourceError` `IMG …/Images/Primary` class is decorative cover-art 404s, suppressed at source by the "drop decorative img telemetry" change — expected to be 0 on current frontend even with coverless items on screen.

### Pull the captured errors

```bash
# Structured client-error lines (last 24h), pretty
kubectl logs -n yay-tsa deploy/yay-tsa-v2-production-backend --since=24h 2>/dev/null \
  | grep '"src":"client_error"' | sed 's/.*: {/{/' | python3 -m json.tool 2>/dev/null | less
# Group by fingerprint (which distinct bugs, how often)
kubectl logs -n yay-tsa deploy/yay-tsa-v2-production-backend --since=24h 2>/dev/null \
  | grep -o '"fp":"[^"]*"' | sort | uniq -c | sort -rn
# Counter breakdown (Prometheus / Grafana): yaytsa_client_errors_total by category,type,version
#   and yaytsa_client_errors_dropped_total{reason=oversize|malformed}
```

### Triage rule (zero-deferral, like every /qa finding)

- **Every distinct fingerprint is a real client-only defect** → reproduce + fix or `gh issue create`. The whole reason this channel exists is that backend `http.server.requests` 5xx can't see these.
- **Post-deploy spike** on any fingerprint right after a release = regression signal — bisect against the `version` tag.
- **Tag-domain health**: `category`/`type`/`version` are server-coerced to finite domains. A surge of `type="other"` = a real JS error class worth adding to the controller allow-list; a surge of distinct legit-looking but non-`main-<sha>` `version=unknown` or a flood of `dropped{reason=malformed}` = someone probing the public endpoint (cardinality/log-flood attempt), not an app bug — confirm bucket4j is holding.
- **Redaction audit (🔴 if it fails)**: spot-check `msg`/`stack` for any surviving `api_key`/`Bearer`/`token=`/`X-Emby-Token`/high-entropy run. A live leak means the redaction missed a vector → fix the server regex immediately (client redaction is bypassable).

### Live pipeline smoke (validates strip-prefix + permitAll + CSRF-off + redaction in prod — closes must-fix #6)

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST https://yay-tsa.com/api/v1/client-errors \
  -H "Content-Type: text/plain" \
  --data-binary '{"category":"runtime","type":"TypeError","message":"QA-SMOKE api_key=LEAKTEST","appVersion":"qa-smoke"}'   # expect 204
kubectl logs -n yay-tsa deploy/yay-tsa-v2-production-backend --since=2m | grep QA-SMOKE   # expect api_key=[REDACTED], single line
```

Synthetic smoke rows carry `version=qa-smoke` → coerced to `unknown` (not a real `main-<sha>` tag), so they never pollute the per-release metric; filter them out of log review by `version!=qa-smoke` in the message.

### Known class: device-heartbeat "Failed to fetch" (best-effort, fixed 2026-06-24)

`POST /v1/me/devices/heartbeat` fires every 15s fire-and-forget (`useDeviceHeartbeat`, caller `.catch` ignored). The core HTTP client logged EVERY network failure at `log.error` → forwarded to telemetry, so transient blips (backend rollout windows, brief offline) surfaced as `category=other/type=other` (~5/24h, confirmed via `increase(yaytsa_client_errors_total[24h])`). Two-tier fix: (1) `MediaServerClient.post(..., bestEffort=true)` downgrades that one call's network/HTTP-error log to `debug` (sink does not forward debug) — the caller already ignores the failure; (2) `useDeviceHeartbeat` escalates to ONE real `log.player.warn` only after 3 consecutive failures (~45s sustained = a genuine "device offline" signal). **Pattern for any fire-and-forget background poll: `bestEffort` to silence per-call transients AND a consecutive-failure escalator to surface sustained outages — never blanket-`debug` (hides real outages), never blanket-`error` (spams telemetry on every transient).**

## Affinity worker: signal-time cursor migration double-counts a pre-populated projection

`core_v2_ml.user_track_affinity` is a **regenerable additive projection** of `core_v2_adaptive.playback_signals`. When the aggregator's incremental watermark was changed from `MAX(updated_at)` (aggregation wall-clock) to a signal-time cursor (`core_v2_ml.affinity_cursor.last_signal_at`, V004), the cursor was seeded to `'epoch'` — correct only for an EMPTY table, but the prior wall-clock aggregator had already populated it. The first signal-time run re-folded ALL history additively onto existing rows → cumulative counters inflated (observed `sum(play_count)=3061` vs `PLAY_START` signal count `1194`, ~2.5× — old overlap + the re-fold compounded). Fix: truncate the projection + replay the cursor from epoch so every signal folds exactly once (live one-shot + durable migration V005). **Verification invariant (use every pass after touching affinity SQL):** `SUM(play_count)` MUST equal `count(*) FROM playback_signals WHERE signal_type='PLAY_START'`, and `SUM(skip_count)` MUST equal the SKIP\_\* signal count — these reconcile the projection against the source-of-truth log. After the fix both matched exactly (1194 / 314). The additive-fold has a known residual late-arrival edge (a signal committing with an EARLIER created_at than an already-consumed one is skipped) — documented in CORRECTNESS.md, needs a per-signal-id ledger or recompute-from-history, not a lookback (which would re-double-count). General rule: a watermark/cursor migration onto a pre-populated additive projection is a double-count trap — seed the cursor to the prior watermark OR truncate+replay; never seed `epoch` against a non-empty additive table.

## MetadataEnricher: a provider-unavailable exception must park too, or it starves the OFFSET-0 tail

The enricher's `selectFreshCandidates` fetches `OFFSET 0` ordered candidates and advances the effective scan window past permanent failures ONLY by parking them in the in-memory cooldown map (`coverAttemptCooldownUntil`). A cover lookup that returns a clean negative (404) is parked and drops out next cycle — but a lookup that **throws** `MetadataProviderUnavailableException` (CAA/archive.org connect/read timeout, 429/503/5xx — note CAA 307-redirects to archive.org, which is the part that times out) was historically only logged, never parked. Such an entity then sits at the head of the `OFFSET 0` query every poll cycle forever: it re-hammers the provider (~once per coverless sibling track, e.g. all chapter-tracks of one audiobook share one release-group) and **starves every coverless entity behind it** — the exact tail-starvation `selectFreshCandidates` exists to prevent. Symptom in Loki: dozens of `Audiobook cover deferred (provider unavailable)` WARNs per hour all citing the **same** `release-group`, while the coverless-count barely moves. Fix: park on the exception path too, with a SHORTER `cover-provider-retry-cooldown-hours` (default 6h) than the clean-negative `cover-retry-cooldown-hours` (168h) — transient outages still self-heal after the short window, permanent timeouts stop hammering and let the window advance. The album/artist paths don't show this in prod because a thrown exception leaves `metadata_checked_at` unset and they retry via the `metadata_checked_at IS NULL` union regardless — but the audiobook path is purely image-driven, so parking is the only throttle. Verify the candidate pool isn't a mis-resolution: `SELECT count(DISTINCT release_group_mbid)` over the coverless set — many distinct albums sharing ONE release-group would be a `ReleaseMatcher` bug, but many chapter-tracks of one album sharing it is normal.

## Live music-surface audiobook exclusion check (black-box)

DB has 175 audiobook-genre tracks in a 7354-track library (~2.4%). The personalized `/Items?IncludeItemTypes=Audio&SortBy=DatePlayed&StartIndex=0` feed includes a `browseTracksRandom` fill, so an UNFILTERED feed leaks ~2 audiobooks per 95-item page statistically. Verify exclusion live: pull the feed with `Fields=Genres` 3× (random fill resamples) and assert 0 items have an `audiobook` genre. After the `MusicSurfaceFilter` wiring on `buildPersonalizedTracks`, all 3 samples were 0/95. `Genres=Audiobook` as an `/Items` query param is NOT the audiobook mechanism (returns the full set) — the marker is per-track `genre`, joined via `entity_genres`/`genres`.
