# QA Methodology — yay-tsa

> Generic QA patterns (SonarCloud rules, pre-commit, ArgoCD refresh, Image Updater, Helm chart versioning, pgbouncer/advisory-lock, accessibility) live in the global `/qa` skill. This file holds project-specific overrides only.

## Mandatory Browser-QA User Flows (NEVER skip these)

Health endpoints, `/Items` HTTP 200, no-pod-restart, and "the page renders" are **liveness signals only** — they do not prove the product works. The audio-stream 500 bug shipped despite all four being green. The only path that catches that class of defect is clicking actual user buttons and asserting externally-observable outcomes. Run every flow below on every QA pass, on **both desktop and mobile (412×915, Pixel 7)**. For each: 0 console errors, 0 unexpected 4xx/5xx in network panel, and the listed positive signal — not "the request returned 200".

1. **Login**
   - Fill username + password → click Sign In → URL becomes `/`.
   - `/api/Users/AuthenticateByName` returns 200; `AccessToken` is stored and next API call carries it (`Authorization: Bearer`).
   - Negative: wrong password → visible error message, no token written.
2. **Library navigation**
   - `/`, `/albums`, `/artists`, `/songs`, `/favorites`, `/settings` — every link in side/bottom nav loads its page.
   - Albums grid renders ≥10 thumbnails; verify with `Array.from(document.querySelectorAll('img')).filter(i => i.naturalWidth>50)` (counting `>0` passes SVG placeholders). At least one response to `/api/Items/<id>/Images/Primary` must be 200; if ALL are 404, image rendering is broken regardless of placeholder fallback.
3. **Album detail → playback start (THE single most critical flow)**
   - Open `/albums/<id>` → click first track's Play.
   - Within 3 s: `<audio>` element exists, `paused === false`, `currentTime > 0`, **`GET /api/Audio/<trackId>/stream` returns `206 Partial Content` (range) or `200`**. Anything 4xx/5xx = streaming broken.
   - Console must NOT contain `MEDIA_ELEMENT_ERROR`, `Failed to load audio`, or `Audio engine error` (usually backend returned non-audio body the HTML5 element couldn't decode).
   - Pause → `paused === true`; resume → playback continues from same `currentTime`.
4. **Seek**
   - Click 75% along track or `audio.currentTime = audio.duration * 0.5`.
   - Within 2 s: `audio.currentTime` within ±1 s of target AND network shows fresh `Range: bytes=<offset>-` returning 206. If same byte-0 request repeats, range support is broken.
5. **Next / previous**
   - Next: player switches to next track, new `/api/Audio/<id>/stream` fires, `currentTrack.Id` changes.
   - Previous (<3 s into playback): actual previous track. Previous (>3 s in): seeks current to 0. Both behaviors required.
6. **Volume + mute**
   - Volume slider changes `audio.volume`. Mute toggle sets `audio.muted=true` AND flips icon. Unmute restores previous volume, not 1.0.
7. **Shuffle + repeat**
   - Shuffle on → queue order changes; off restores. Repeat-one → end of track restarts same. Repeat-all → end of last queue item plays first.
8. **Favorites**
   - Heart on track → `POST /api/UserFavoriteItems/<id>` 200, icon fills, `/api/Items?Filters=IsFavorite` includes it. Click again → DELETE 200, icon empties.
9. **Search** — type ≥3 chars → `/api/Items?SearchTerm=…&Recursive=true` ≤50 items in dropdown; click navigates to detail.
10. **Lyrics + karaoke** (where applicable) — synchronized lines; toggle karaoke → secondary audio loads `/api/Karaoke/<id>/instrumental` 200; vocals toggle switches `/instrumental` ↔ `/vocals`.
11. **Queue panel** — current highlighted, future entries in order, click jumps, drag-reorder persists.
12. **Devices + multi-device** — current browser appears with device name; second tab appears within ~15 s (heartbeat). EventSource `new EventSource('/api/v1/me/devices/events')` opens (readyState 1) and receives `ready` within 5 s. SSE 500 = auth filter chain stripped the cookie (distinct from streaming 500).
13. **Settings** — library scan POST 202/200, scan progress visible. Logout → token cleared, redirect to `/login`, next `/api/Items` returns 401.
14. **Mobile-only (412×915)** — bottom tab bar visible, sidebar hidden. PlayerBar mini-bar visible above tab bar; click opens MobileFullPlayer. Shuffle/repeat/queue/lyrics reachable with ≥44 px hit areas. Pull-to-refresh does not destroy player state.
15. **PWA + background tab** — service worker registers; "Install" prompt available (PC). Play → background tab 30 s → return: playback continues OR `visibilitychange` recovery within 2 s. Do NOT accept "audio resumes 5 minutes later".

**If any of the above fails, the bug is shipped to production** — `/qa` is not complete until every one is green on the deployed image whose tag matches `kubectl get deployment ... -o jsonpath='{.spec.template.spec.containers[0].image}'`.

## QA Session Findings (2026-05-19, second pass)

- **`/api/v1/sessions/{id}/queue` exposes `addedReason` per entry** — `seed-track` / `bootstrap-similarity` / `bootstrap-affinity` / `llm-dj`. Browser QA must include `await fetch('/api/v1/sessions/{id}/queue').then(j => j.tracks.map(t => t.addedReason))` after clicking a Radio card; if NONE of the entries are `bootstrap-similarity`, the embedding path is dead (HNSW disabled, MERT column NULL, or controller bypassing `findSimilarTracks`). The bare audio-playing signal does NOT distinguish "radio loaded a similarity-driven queue" from "radio fell back to generic top-affinity".
- **`LlmOrchestrator` floods backend logs when Anthropic credit balance is exhausted.** Pattern: `BadRequestException: 400 ... "Your credit balance is too low"`. `@Scheduled` worker fires once per active session per tick (~minute), each emits a multi-line stack trace, drowning the rest of the log. Fix lives in `LlmClient`: catch `BadRequestException`, substring-match `"credit balance"`, set a time-based `coldUntil` `AtomicReference`, short-circuit subsequent calls until the breaker expires. Single-line WARN on transition open; no log on subsequent suppressions. Tied to property `yaytsa.llm.credit-circuit-breaker-minutes` (default 60). Also feature-flag the whole `LlmOrchestrator` via `yaytsa.llm.enabled=false` in the relevant `values.prod.yaml` when there is no LLM budget — the breaker is defense-in-depth, the flag is the off switch.
- **Two CI workflows, easy to mistake one for the other.** `ci.yml` is the main pipeline (pre-commit + image builds + post-deploy autoqa); `v2-ci.yml` is a Kotlin-only docker build. `gh run list --workflow=CI` filters to `ci.yml`; `gh run list --workflow="CI (v2)"` to the v2-ci.yml. **The post-deploy-qa job lives in `ci.yml` only.** Use `--workflow=ci.yml` (lowercase, file name) to find autoqa-bearing runs. Confirm by `gh run view <id> --json jobs` — if the jobs list contains `Post-Deploy QA`, that's the one.
- **Rapid-fire pushes cancel CI mid-flight.** Each push triggers ci.yml + v2-ci.yml; concurrency-group cancels in-flight runs from earlier commits. Pushing 4 commits in 10 minutes leaves only the latest run alive. After QA-pass commits, expect `gh run list` to show many "cancelled" — that's normal, not a bug. The only authoritative run is the most recent `in_progress` or `success`.
- **Cross-schema worker pattern (signal → affinity).** Use `JdbcTemplate` (auto-configured by `spring-boot-starter-data-jpa` which `infra-ml-worker` already depends on) for cross-schema native SQL. UPSERT with `ON CONFLICT (...) DO UPDATE SET col = uta.col + EXCLUDED.col` for additive counters. Watermark = `SELECT MAX(updated_at) FROM <dest>` — no extra state table needed. Falls back to `Instant.EPOCH` on first run; subsequent ticks read the previous run's commit time. Schema isolation at the application layer (no module-dependency edge between `core-application/ml` and `core-application/adaptive`) doesn't block the worker — Postgres doesn't care about Java module boundaries.
- **`/qa` test-account work needs the primary CNPG pod, not a replica.** `kubectl get cluster -n shared-database -o jsonpath='{.items[0].status.targetPrimary}'` returns the pod name; replicas reject mutations with `read-only transaction`. Pattern: `kubectl exec -n shared-database <primary> -c postgres -- psql -U postgres -d yaytsa_production -c "..."` for one-off SELECTs and DELETEs scoped to broken rows surfaced via GH issues.

## QA Session Findings (2026-05-24)

- **Anthropic/external-LLM integration removed; `LlmClient` is now a stub.** `infra-llm/LlmClient.kt` no longer imports the Anthropic SDK, takes no `api-key`/`model`/circuit-breaker constructor args, and `complete()` returns `null`. The credit-balance circuit-breaker (see 2026-05-19 note) is **gone** — there is no external LLM call left to flood logs, so that whole failure mode is retired. The `LlmOrchestrator`/ports/`llm_decisions` audit/protocol scaffolding is intentionally kept so a future local LLM drops into one method. Config keys `yaytsa.llm.api-key`/`api-url` and Helm `apiKeySecret`/`apiUrl` were dropped; `enabled` (default false) + `model` (now `local-stub`) remain. Adaptive queue keeps working on ML-only recommendations with the stub. The `.github/workflows/claude.yml` PR-review bot was also removed per the same task.
- **Stale image rows affect ARTISTS too, not just albums** (extends the "Orphan album image rows after disk reorganisation" note). Crawler surfaced `404 /api/Items/<artist-id>/Images/Primary` for `Scars On Broadway`: the `images.path` pointed at `/media/Daron Malakian and Scars On Broadway/Band.jpg` (the band's full/old folder name) while the disk folder is `/media/Scars On Broadway/`. The real `Band.jpg` existed in the correct folder, so the fix is **repoint, not delete** (preserves the image): `UPDATE core_v2_library.images SET path='/media/<correct>/Band.jpg' WHERE entity_id='<id>' AND path='<stale>'`. Verify with an authed `GET /Items/<id>/Images/Primary` → 200 image/jpeg. Detection across the library: image rows whose top-level `/media/<folder>` doesn't exist on disk (the v2 scanner never inserts/repairs image rows, so v1-ETL image paths rot whenever folders are renamed).
- **`post-deploy-qa` is structurally red on two documented autoqa false positives** — do NOT chase either as a yay-tsa backend bug; both are filed upstream and the gate goes green only after autoqa fixes them:
  - **ZAP HIGH "SQL Injection" on `/rest/getArtist.view?...f=f+AND+1=1+--`** — the `/rest/*` Subsonic surface is rate-limited (bucket4j 10/min); the flagged URL returned **429**, so ZAP's boolean differential is edge-side, never reaching the (parameterized JPA) query. `aggregate-gate.mjs` counts any `riskcode==3` without checking the response code. Filed: `nikolay-e/autoqa#7`.
  - **Schemathesis "106 operations returned authentication errors"** — status breakdown was 106×401 + 1×403 + 1×400, **zero 5xx, zero schema violations**. The OpenAPI spec already documents 401 on every op (`OpenApiConfig.globalResponsesCustomizer`), and a valid bearer token returns 200 on every flagged read endpoint (verified manually). The 401s are schemathesis's auth-negative `--checks all` coverage, which the gate (`grep '\d+ failed'`) counts as failures. Filed: `nikolay-e/autoqa#8`.
- **Fast API-level smoke that catches the documented bug classes without a full Playwright session** (proportionate for backend-only / infra-only changes; crawler already covers page render + console + axe + CSP on ~30 logged-in pages): login → `GET /Items` (read, 200) → `GET /Audio/<id>/stream -r 0-100000` (binary, expect 206 + `audio/*`) → `POST`+`DELETE /UserFavoriteItems/<id>` with an `IsFavorite` list check in between (write, 200/present/200). All green 2026-05-24 against `main-c606b1d`.

## ML / Recommendation Coverage Gaps (2026-05-19)

This is the structural failure of the QA pipeline as it stood until 2026-05-19: **ML embeddings, HNSW indexes, taste profiles, and `user_track_affinity` all existed in the DB and shipped with v2, but no production code path read or wrote them.** Specifically:

- `MlQueryPort.findSimilarTracks` / pgvector `<=>` similarity search: **did not exist**. The HNSW indexes on `embedding_mert`/`clap`/`discogs`/`musicnn` (V002 migration) had no callers — they were dead storage.
- `core_v2_ml.user_track_affinity`: **never written by production code in v2**. Only the v1→v2 ETL migration script (`scripts/etl-migrate.sql`) ever inserted rows. `playback_signals` rows accumulated from user skips/completions/thumbs but no worker aggregated them. Affinity scores were frozen at the v1 cutover state forever.
- `/v1/recommend/radio/seeds` returned the same top-8 affinity tracks as `/v1/recommend/daily-mix` — `buildRecommendedSeeds()` was literally `= buildRecommendedTracks(...)`. Two RPC paths, one set of bytes. The radio row on the home page rendered as "first 8 entries of Daily Mix" with duplicate artists.
- `PreferenceContract.redLines` was sent to the LLM-DJ prompt but **never filtered candidate tracks at query time**. A user could declare "never play screamo" and still get screamo in their Daily Mix and Radio.
- LLM-DJ prompt didn't include `session.seedTrackId` or any embedding-similar candidates — Claude was choosing from "user's overall top affinity" regardless of which radio station was clicked.

### Why the QA pipeline missed all of it

Pre-deploy gates green, post-deploy smoke checks green, `npm run test:integration` green, every Mandatory Browser-QA flow green. The bug class slipped through because every existing test is structurally myopic:

- **`MlQueryPortTest`** (Testcontainers) tests each port method in isolation: `getTrackFeatures returns row`, `getTopAffinities returns ordered list`. Passing those tests proves the JPA wiring works — it says nothing about whether anything **calls** the port from a real recommendation path. Two passing port methods, zero callers, zero red lights.
- **Hibernate startup validation** confirms `@Query` placeholders match parameters. It does not confirm the query is reachable from any controller, scheduler, or worker.
- **ETL validation script** (`yay-tsa-v2/scripts/validate-migration.sql`) counts rows post-cutover. `SELECT count(*) FROM core_v2_ml.user_track_affinity` returned the v1-imported number → green. Whether new signals after cutover get aggregated → unmeasured.
- **`/qa` browser flows** click Play and assert audio bytes flow — they don't measure "are these tracks actually personalised". A user gets identical content on a fresh login as on a 6-month-old account; both feel "working" to a smoke test.
- **Schemathesis / ZAP** exercise endpoint syntax and security. They never assert _recommendation quality_ because the assertion language for "is this a good recommendation" doesn't fit OpenAPI/HTTP-status testing.
- **No metric tracks "fraction of recommendation responses that actually invoked the ML port"** — adding a Micrometer counter per call site would have surfaced the gap in Grafana within a day of v2 going live.

### Test patterns that MUST exist going forward

Add these to the project's integration test suite — not as unit tests, as **end-to-end DB-backed integration tests** (per workspace rule: real Postgres, real worker, real signals):

1. **Embeddings actually drive bootstrap** — after `POST /v1/sessions {seed_track_id: X}` + `POST /v1/sessions/{id}/queue/refresh`, the resulting queue's track set must `INTERSECT` with `mlQuery.findSimilarTracks(X, 10)` by ≥ 50%. If the queue is purely top-affinity instead, the test fails. Run against a fixture library with known seeded MERT embeddings so similarity is deterministic.
2. **`POST /Sessions/Playing/Stopped` + `signal_type=PLAY_COMPLETE` raises affinity_score within one worker tick.** Seed `user_track_affinity` for `(test_user, track_X)` at score 0.0, fire the signal, sleep past `yaytsa.affinity.poll-interval-ms`, assert `affinity_score > 0`. If the column doesn't move, the worker is dead or unwired — exactly the case we shipped for 6 months.
3. **`POST /Sessions/Playing/Stopped` with `signal_type=SKIP_EARLY` lowers affinity_score** (mirror of above; ensures negative-feedback path also flows).
4. **`PUT /v1/users/{id}/preferences { redLines: "metal" }` removes tracks tagged `metal` from `GET /v1/recommend/daily-mix` and `/v1/recommend/radio/seeds`.** Insert a known metal track into the user's top affinities, set the red line, hit both endpoints, assert the track is absent. Without this we ship the "user sets red line, recommendation ignores it" regression every time someone touches the recommendation path.
5. **Two distinct radio seeds yield two non-identical track sets.** Pick two seeds with no shared HNSW neighbours in the fixture, bootstrap both queues, assert `set(queue_A) ∩ set(queue_B) ≤ 1` (the seed itself). This is the test that would have caught "Radio = Daily Mix" the first day.
6. **No production code referencing `MlQueryPort` reaches it via a Spring proxy whose target is missing.** ArchUnit rule: every `@Autowired`/constructor-injected `MlQueryPort` must have a `@Repository`-annotated implementation reachable through `@SpringBootApplication` scan. The current rule prevents this from regressing if a future refactor accidentally drops `@Repository`.

### Operational signal to add

A Micrometer gauge `yaytsa.ml.affinity.rows_total` and `yaytsa.ml.affinity.last_updated_at` exposed via `/manage/prometheus`. A Grafana alert "`time() - max(yaytsa_ml_affinity_last_updated_at) > 6h`" pages immediately when the signal→affinity worker silently stops processing — the failure mode that ran for 6+ months without anyone noticing. Same pattern for `yaytsa.ml.similarity.queries_total` (counter) — zero increment over a 24h window proves the recommendation path is bypassing ML.

### Generalised lesson

Schema migrations that introduce new capability (HNSW indexes, embedding columns, signal logs) are dangerous in a way that bug fixes aren't: the migration succeeds and "looks deployed", but the read- and write-side code that gives the schema meaning may be entirely missing or stubbed. **Treat every new schema feature as untrusted until an integration test exercises a controller / worker that demonstrably reads or writes through it.** The test is the only line of defense — staring at the migration doesn't prove anything will ever call it.

## yay-tsa Specific Backend Diagnostics

- `actuator/health` only proves Tomcat started. Says nothing about `/Items` returning data, `/Audio/stream` returning audio bytes, `/Images/Primary` returning image bytes, or JPA `EntityManager` being wired. **A green health probe with broken streaming is the most common "deploy looks fine, users complain" scenario.**
- After every deploy, exercise one **read** path (`/Items` with auth → ≥1 row), one **binary** path (`/Audio/<id>/stream` with Range → 206 with `audio/*` Content-Type), and one **write** path (favorite + unfavorite).
- Backend log `Unhandled exception` + `HttpMessageNotWritableException` is the unambiguous signal of "server returned 5xx with body the client can't decode". Grep before declaring a release healthy: `kubectl logs -n yay-tsa-v2-production deployment/... --since=5m | grep -E "Unhandled|MessageNotWritable|MessageConversion"`.

## Mobile E2E Tests (project-specific)

- Mobile viewport tests use separate Playwright project (`[mobile]`) with Pixel 7 dimensions (412 px).
- PlayerBar renders a mini-bar on mobile (`md:hidden`) and hides it with CSS `invisible` when full player opens — `expect(playerBar.playerBar).toBeVisible()` fails while full player is open; use `expect(playerBar.currentTrackTitle).toBeVisible()`.
- Test IDs must be present in BOTH PlayerBar mini-bar AND MobileFullPlayer for `:visible` selectors to work across states.
- Modal components must use `createPortal` to escape invisible parent containers.
- `ensureControlsVisible()` opens the full player on mobile, which changes which elements are visible — test locators using `:visible` must account for this state change.
- `getByRole('button', { name: 'Shuffle' })` can match AlbumDetailPage text-button — use `locator('button[aria-label="Shuffle"][aria-pressed]:visible')` for player controls.
- Badge text on mobile may include unit suffix (e.g., "15m") — use `Number.parseInt(text ?? '0', 10)`, not `Number(text)`.
- `setPointerCapture` throws `DOMException` for synthetic pointer events — guard with try/catch in `handlePointerDown`.
- Monkey test 300 s timeout on mobile CI is expected when iterations don't complete.

## Karaoke / Audio Separator

- Stems PVC must be mounted in BOTH audio-separator AND backend deployments — separator writes, backend reads.
- After separation completes, `getStatus()` validates stem files exist via `validateStemFilesExist()` — if backend can't see files, it resets `karaokeReady=false`.
- `tryRecoverFromOrphanedFiles()` can recover DB state from existing stem files without re-processing.
- Stem path pattern: `/app/stems/{trackId}/{filename}_instrumental.wav` and `_vocals.wav`.
- Separator health check shows model info and device (cuda/cpu) — useful for diagnosing GPU issues.

## E2E Test Flakiness (project-specific)

- `previous()` in player store uses `engine.getCurrentTime()` which reads `audio.currentTime` — can be stale after `seek(0)` in headless Chrome.
- Fix: read from timing store (`useTimingStore.getState().currentTime`) updated synchronously on seek.
- Timing store `updateTiming()` uses `requestAnimationFrame` batching — seek must use synchronous `seekTo()` to avoid RAF race where audio timeupdate overwrites pending seek before frame fires.
- `seek()` must always update timing store even when `controller.isActive` — `pause()` uses `void controller.interrupt()` (fire-and-forget), so controller may still be active when test calls seek immediately after pause.
- After `seek(0)` in page object, wait for `audio.currentTime < 1` before proceeding — otherwise `clickPreviousAndWait` fires before seek completes.
- `queue.advanceTo(trackId)` must skip `recordCurrentToHistory()` when target index equals current — otherwise `commitPlaybackSideEffects` pushes just-played track to history, `previous()` pops it and goes nowhere. Same guard applies to `jumpTo(index)` for self-jumps.
- Lighthouse LCP budget 2500 ms is too tight for GitHub Actions runners — 3000 ms is more realistic for CI.

## Multi-Device Sync

- SSE hook `useDeviceEvents` must fetch device list on mount — otherwise `updateDeviceState` maps over empty array and drops all events.
- When SSE event arrives for unknown `deviceId`, refetch full device list.
- Backend `broadcastDeviceState` should include `nowPlayingItemName` alongside `nowPlayingItemId` — otherwise DevicesPanel shows position but no track name.
- Crawler timeout on SSE-heavy pages (`networkidle` never fires) is expected.

## API Client Tests

- `MediaServerClient` sends `Authorization: Bearer <token>` for actual auth credential, and `X-Emby-Authorization: MediaBrowser Client="…", Device="…", …` purely as client-identity metadata (no token).
- Tests must assert against `Authorization: Bearer` for token and against `X-Emby-Authorization` only for `Client="…"` / `Device="…"` / `Version="…"` substrings — never expect `Token="…"` in `X-Emby-Authorization`. Proxy chain strips non-Bearer auth, so `Bearer` header is the only one that survives end-to-end.

## Background Tab Playback

- Browser throttles `setTimeout`/`setInterval` in background tabs (Chrome: 1 s minimum, frozen tabs paused entirely).
- `AudioContext` gets suspended in background — `linearRampToValueAtTime` stops, `currentTime` freezes.
- `audioContext.resume()` may reject without user gesture — must NOT block `HTMLAudioElement.play()`. Fix: fire-and-forget `audioContext.resume()` (don't await), let `visibilitychange` handler recover.
- Recovery pattern: on `visibilitychange` to visible, check `isPlaying && paused && ended` → force advance via `controller.interrupt`.
- `transitionToPreloaded()` and `seamlessSwitch()` must also attempt AudioContext resume before secondary element play.

## Backend OOM / Playback Stability

- Backend OOMKilled at 2Gi limit with JVM heap 1536m — idle ~1112Mi leaves no room for burst image processing.
- Concurrent `/Images/Primary` (50+) each load full image, decode to BufferedImage, resize, convert to WebP — exhausts heap rapidly.
- Fix: limit concurrent image processing with semaphore (`MAX_CONCURRENT_IMAGE_PROCESSING=4`), increase backend memory limit to 3Gi.
- Audio separator OOMKilled (27 restarts) at 4Gi with BS-Roformer — needs 6Gi.
- Karaoke backfill retries failed tracks indefinitely — add `karaoke_fail_count` column, skip after 3 failures.
- Entrypoint computes JVM heap as 75% of container limit — increasing container limit automatically increases heap.
- When backend OOMKills, all active stream connections break mid-playback — this is the "sometimes plays, sometimes doesn't" symptom.

## Post-Deploy QA

- Wait-for-rollout step must **fail** on version mismatch (not just warn) — otherwise Schemathesis/ZAP/crawler run against old version and all fail on auth.
- Wait-for-rollout MUST keep its auth check. Auth round-trip is a deeper liveness signal: proves DB connectivity, auth filter, Bearer token issuance. **If you find yourself wanting to strip auth, the real bug is that auth-failure and rollout-not-ready both manifest as `VERSION=''`.** Fix: distinguish auth failure (credential/user → fail fast after 3-4 attempts naming username and HTTP status) from version-mismatch (rollout in progress → keep polling).
- Build scripts (`generate-icons.js`) need `/* eslint-disable no-console */`.
- **"Backend version mismatch after 10min: got '', expected 'main-<sha>'" has two distinct root causes:**
  1. **Argo CD Image Updater write-back failing**. Check `kubectl logs -n argocd -l app.kubernetes.io/name=argocd-image-updater --since=15m | grep yay-tsa`. Common cause: annotation lists an image alias whose `helm.image-tag` parameter resolves to a key the rendered chart doesn't produce (disabled service). Image Updater errors "parameter not found" and aborts the **entire batch** — backend/frontend updates roll back too. Fix: drop disabled service from BOTH the `argocd-image-updater.argoproj.io/image-list` annotation in the gitops Application AND the `ImageUpdater` CR's `applicationRefs[].images[]`.
  2. **QA test user doesn't exist in prod DB or `QA_PASSWORD` is stale**. Script silently treats auth failure as `VERSION=''`. Verify with `kubectl exec -n shared-database <primary-pod> -c postgres -- psql -U postgres -d yaytsa_production -c "SELECT username, is_active FROM users WHERE username='test';"`. If absent, INSERT via the **primary** pod (replicas reject as "read-only transaction"); find primary with `kubectl get cluster -n shared-database -o jsonpath='{.items[0].status.targetPrimary}'`. Then `gh secret set QA_PASSWORD --repo nikolay-e/yay-tsa`; also stash in Keychain as `yay-tsa-qa-password`.

## P2 DRY refactor Dockerfile gotchas (2026-05-19 /qa)

- **`scripts/gen-constants.mjs`** at repo root reads `shared-constants.yaml` (also repo root) and emits `packages/core/src/generated/constants.ts`. Both files are now part of `npm run build`, so the web Dockerfile MUST `COPY scripts/ ./scripts/` and `COPY shared-constants.yaml ./` after the `packages/`+`apps/` COPY. Missing this fails frontend image build with `Error: Cannot find module '/app/scripts/gen-constants.mjs'`.
- **`core-domain/shared/build.gradle.kts`** declares a `generateSharedConstants` task with input `rootProject.file("../shared-constants.yaml")`. `rootProject` is `yay-tsa-v2/`, so the YAML must live at the repo root one level above. v2 Dockerfile context now must be the repo root (`context: .` in `v2-ci.yml`), with `dockerfile: yay-tsa-v2/Dockerfile`. Every `COPY` inside `yay-tsa-v2/Dockerfile` carries the `yay-tsa-v2/` prefix, plus `COPY shared-constants.yaml ./` at the parent WORKDIR before `WORKDIR /app/yay-tsa-v2`.
- **`adapter-shared/` module** (added in P2.C) must be explicitly copied via COPY in BOTH the early `build.gradle.kts`-only phase and the source-copy phase of the multi-stage Dockerfile. Missing it = `Project ':adapter-shared' not found` at Gradle configure time. Same applies to any future `adapter-*` module.
- **`build-logic/` included build** (added in P2.A) must be copied via COPY before `gradlew dependencies` runs. It's referenced from `settings.gradle.kts` as `pluginManagement { includeBuild("build-logic") }`. Without it, plugin resolution fails on `id("yaytsa.infra-persistence")`.

## autoqa pipeline gotchas (2026-05-17 QA — Part 3)

- **Schemathesis `--checks all` triggers Cloudflare 502 swarm**. Every coverage/fuzzing test case that fires an HTTP `QUERY` verb or `DELETE` against `/Admin/*` hits Cloudflare → CF Tunnel can't reach origin → CF returns its own `502 Bad Gateway` HTML body. Schemathesis logs 226 failures and 56 errors that look like backend bugs but are entirely edge-side. **Telltale:** body starts with `<!DOCTYPE html>` and contains `<title>yay-tsa.com | 502: Bad gateway</title>` or `Cloudflare Tunnel error`. Triage: read 1-2 sample failures; if every body is CF HTML, the run is CF-throttled, not buggy. The fix is upstream (CF tunnel resilience, autoqa request pacing) — file against `nikolay-e/autoqa`, do not chase ghosts in backend code. Schemathesis without `--checks all` (just Stateful + basic Fuzzing) is cleaner: 109 failures vs 226, easier to triage.
- **Schemathesis flags 401 on admin endpoints as `Undocumented HTTP status code`.** OpenAPI declares them as `200` only; the actual response is `401` for non-admin QA test user (correct security behavior). Fix is schema-side — add `401`/`403` to documented responses for admin paths in `/Admin/*`. Until then these are warnings, not bugs.
- **`POST /Users/AuthenticateByName` rejected camelCase aliases.** Per QA conventions ("PascalCase request/response keys for Jellyfin compatibility (camelCase aliases also accepted)") — but Schemathesis sending `{"username":"","pw":""}` got 400 because the Kotlin data class `LoginRequest` declared only `Username`/`Pw`. Fix: add `@JsonAlias("username")` / `@JsonAlias("pw")` on the request fields. Same pattern needs to ripple to any other endpoint where Jackson camelCase fallback isn't yet wired.
- **`POST /Lyrics/{trackId}/fetch` violated its own springdoc-inferred schema.** Return type `ResponseEntity<Map<String, Any>>` makes springdoc emit `{type: object, additionalProperties: {type: object}}`. Actual body `{"trackId": "...", "lyrics": []}` has an array value, which violates `additionalProperties: object`. Schemathesis flags `Response violates schema: [] is not of type "object"`. Fix: replace `Map<String, Any>` with a typed DTO (e.g. `LyricsFetchResponse(trackId, lyrics: List<LyricLine>)`). Rule of thumb: **any controller returning `Map<String, Any>` is a future Schemathesis finding**.

## autoqa pipeline gotchas (2026-05-17 QA — Part 2)

- **`QA_AUTH_TOKEN` leaks plaintext into the workflow run log** when the autoqa action writes it to `$GITHUB_ENV` without calling `::add-mask::` first. GitHub Actions only redacts the literal `secrets.QA_PASSWORD` value — anything _derived_ from it (the bearer token returned by `/Users/AuthenticateByName`) is visible in every subsequent step's `env:` group header. Public-repo workflow logs expose the token to the whole internet for the lifetime of that session token. Fix lives in autoqa `scripts/auth.sh`: add `echo "::add-mask::${TOKEN}"` BEFORE writing `QA_AUTH_TOKEN=` to `$GITHUB_ENV` (autoqa SHA `1912545+`). Always assume any QA-test-user token shown in a public CI log is compromised — rotate by calling `/Sessions/Logout` (once the next bullet's bug is fixed) or by `UPDATE core_v2_auth.api_tokens SET revoked=true WHERE token = encode(sha256(:hex), 'hex')` on the primary CNPG pod.
- **`POST /Sessions/Logout` was a no-op for the only auth surface clients actually use (Bearer token).** Controller checked `auth is JellyfinAuthentication`, but `ApiTokenAuthFilter` runs FIRST in the chain and sets `YaytsaAuthentication` for any `Authorization: Bearer …` request. The Jellyfin filter's `if (auth == null)` short-circuit then prevented it from overriding. Controller saw `YaytsaAuthentication`, the `is JellyfinAuthentication` check returned false, `RevokeApiToken` was never dispatched, and the token stayed live forever. **Logout returned 204 in both branches**, so client SDKs and the PWA never noticed. Fix: read `auth.name` (user id) and `auth.credentials` (raw token) via the standard Spring `Authentication` interface so the path works for every filter — and surface the use-case Result instead of dropping it on the floor. Also retry once on OCC conflict (high-traffic users have their version bumped by concurrent CI logins, lock-step OCC otherwise loses).
- **391 lingering tokens for the `test` QA user** after weeks of CI runs (only 5 revoked). Every CI run creates a new `api_tokens` row via login, none of them are ever revoked because of the bug above. After fixing logout, also consider an admin/job sweep that revokes tokens older than N days for service users. Quick count: `SELECT COUNT(*) FILTER (WHERE NOT revoked) FROM core_v2_auth.api_tokens WHERE user_id = '<test-user-id>';`.

## autoqa pipeline gotchas (2026-05-17 QA)

- **Schemathesis hits the SPA shell when `--url` and crawler url share a value but API mounts on a path prefix.** With Traefik strip-prefix middleware, backend OpenAPI emits bare paths (`/Admin/Cache`, `/Items`, etc.) and `servers: [{url: "http://yay-tsa.com/api"}]`. autoqa unconditionally passed `--url ${inputs.url}` (i.e. `https://yay-tsa.com`), so Schemathesis joined that with `/Admin/Cache` → hit frontend nginx → got 405 (DELETE on static files) or 200 returning `index.html` → 159 false-positive failures, zero real backend coverage. Fix needs autoqa to support a separate `schemathesis-base-url` input (post-`9c2f6c0` autoqa: pin to `601af09` and set `schemathesis-base-url: https://yay-tsa.com/api`).
- **autoqa composite step aborts on first tool failure unless each step has `continue-on-error`.** Schemathesis exit 1 silently killed ZAP + crawler + axe + AuthZ. Whole pipeline showed `Run AutoQA: failed`, ZAP-gate step then errored "ZAP report not generated", and crawler/axe never ran. Post-`601af09` autoqa wraps every tool step with `continue-on-error: true` + `if: always()` so they each run independently and consumers gate on report files (existing pattern for `zap-report.json`).
- **Spring Boot springdoc emits `http://` in `servers[0].url` despite `forward-headers-strategy: framework` + `protocol-header: X-Forwarded-Proto`.** Behind Cloudflare → Traefik → backend the original scheme is `https` but the spec's `servers` block carries `http`. Hasn't bitten anything yet because Schemathesis is configured with explicit `--url`. Worth fixing if any future tool defers to the spec's scheme.

## Scanner: placeholder tags vs missing tags (2026-05-17 QA — Part 2)

- Some FLAC rips carry literal `##### ######` in TITLE/ARTIST/ALBUM where the ripper failed to encode Cyrillic. The scanner used those values verbatim, producing a separate `##### ######` artist + `######### # ######` album alongside the correct folder-derived entities. The track name fallback for tag-less files also leaked filenames like `01 - Eyeless` (Slipknot is the worst offender — no TITLE tag on any track). Fix shape in `LibraryWriter`:
  - `String.usableTag()`: treat `^[#?\s]+$` as missing (returns null), so folder-derived fallback kicks in for artist/album and filename-derived fallback kicks in for title.
  - `stripTrackNumberPrefix(filename)`: strip leading `\d{1,3}\s*[-._]\s*` when title falls back to `nameWithoutExtension`.
  - `repairExistingTrackLinkage` extended to also flip `existingTrack.name` (and `sortName`) when the existing name matches the filename-prefix pattern and the freshly-derived value is different. Required flipping `LibraryEntityJpa.name`/`sortName` from `val` to `var`.
- Detection pattern (drop-in SQL):

  ```sql
  SELECT name, source_path
  FROM core_v2_library.entities
  WHERE entity_type IN ('ARTIST','ALBUM','TRACK')
    AND name ~ '^[#?\s]+$'
  LIMIT 20;
  ```

  Also worth grepping the live `/api/Items?Recursive=true&Limit=N` response for `^[#\s]+$` names, since that's what the user actually sees.

- Verifying the source: `kubectl exec -n yay-tsa-v2-production deployment/yay-tsa-v2-production-backend -- ffprobe -hide_banner -show_format "<file>"`. If `ffprobe` says `TITLE: #########`, the FLAC tag itself is the placeholder — code can only fall back, not "fix" it. Long-term cure is re-tagging the source files.

## Browser-QA: known-good Slipknot fingerprint

- The Slipknot album page (`/albums/f81c535f-0899-4bf9-a81f-6823906c7291`) is the canonical regression case for filename-fallback titles — every track on every album lacks ID3 TITLE. Before any scanner change, snapshot the visible `aria-label="Play …"` strings; after the change they should read `Eyeless`, `Spit It Out`, `Wait And Bleed`, … not `01 - Eyeless`, `06 - Spit It Out`, `04 - Wait And Bleed`.
- The Агата Кристи album `/albums/436486a0-cb15-43a9-a0d7-d8a69d3fc2e0` is the canonical case for placeholder-tag rejection — the FLAC stores `TITLE/ARTIST/ALBUM` as `##### ######`, so the API must serve the folder-derived names (`Агата Кристи / Коварство и любовь / Viva Kalman!`). Run both immediately after a scanner deploy.

## Cleanup pattern for orphan/broken artist+album rows

- When the broken-artist/broken-album row has tracks pointing to it (cross-schema by-value refs in `play_history`, `favorites`, …), follow this order in a single transaction:
  1. `UPDATE core_v2_playback.play_history SET item_id = <keep> WHERE item_id = <drop>;` (and analogous for every cross-schema table — see "ETL leaves dup track entities" further down for the full list).
  2. `DELETE FROM core_v2_library.entities WHERE id IN (<drop track ids>);` — CASCADE drops `audio_tracks`, trigger `cleanup_empty_parent` drops the now-empty album then artist.
- If the broken artist/album has NO tracks (orphan because the scanner re-linked the only track), the trigger won't fire (it only runs on delete of children with `parent_id IS NOT NULL`). Manually delete:

  ```sql
  DELETE FROM core_v2_library.entities WHERE id = '<broken-album-id>';
  DELETE FROM core_v2_library.entities WHERE id = '<broken-artist-id>'
    AND NOT EXISTS (SELECT 1 FROM core_v2_library.entities WHERE parent_id = '<broken-artist-id>');
  ```

## Image Updater write-back lag — manual `refresh=hard` unblocks

- After a successful CI build that produces a new image tag (e.g. `main-6bf119f`), Image Updater may sit on the previous tag (`main-30bc14c`) for **>1 hour** before reconciling. Symptom: `kubectl get deployments -n yay-tsa-v2-production -o jsonpath='{...image}'` shows old tag, but GHCR lists newer tag created earlier (`gh api /users/.../packages/container/.../versions`). Image Updater logs are silent during the gap.
- Unblock: `kubectl annotate application yay-tsa-v2-production -n argocd argocd-image-updater.argoproj.io/refresh="$(date +%s)" --overwrite` → wait ~20s → Image Updater logs show `Setting new image to ...main-6bf119f` + commits to gitops. Follow with `kubectl annotate application yay-tsa-v2-production -n argocd argocd.argoproj.io/refresh=hard --overwrite` if Argo CD still shows old image.
- Worth investigating: is the Image Updater polling interval too long, or does `update-strategy: newest-build` rely on a timestamp source that races GHCR's `created_at` field? For now, the manual refresh is the documented workaround.

## kubectl flakes on local control plane

- `kubectl ... -> read: connection reset by peer` against `https://localhost:16443/api/...` from the laptop usually means the K3s API server / kubelet is restarting or WARP/VPN flipped state. Not actionable from this side — wait a minute and retry. Do not interpret as cluster-side outage unless cluster apps also stop responding through their public URLs.

## Backend bugs surfaced by `packages/core` integration tests (2026-05-17 QA)

Run integration tests against deployed prod periodically — they catch missing endpoints that `/Items` smoke checks miss:

```bash
QA_PASSWORD=$(security find-generic-password -a "$USER" -s "yay-tsa-qa-password" -w) \
  YAYTSA_SERVER_URL=https://yay-tsa.com/api YAYTSA_TEST_USERNAME=test YAYTSA_TEST_PASSWORD="$QA_PASSWORD" \
  npm run test
```

Bugs found and fixed today:

- **`GET /Items/{id}` returns 404 for Playlist UUIDs.** `JellyfinItemsController.getItem` only checks Track/Album/Artist via `libraryQueries`. Playlists live in `core_v2_playlists` so the PWA's `getPlaylist(id)` (which calls `/Items/{id}`) always 404'd after `POST /Playlists`. Fix: add `playlistQueries.find(PlaylistId(itemId))?.toBaseItem()` to the fallback chain with `Type: "Playlist"` and `childCount = tracks.size`.
- **`POST /Sessions/Playing*` accepted any ItemId string and returned 204.** Garbage IDs like `"invalid-track-id-12345"` silently no-op'd. Fix: `UUID.fromString(info.itemId)` guard returning 400 on all three (`/Playing`, `/Playing/Progress`, `/Playing/Stopped`).
- **Library scanner inserts zero-duration tracks.** CD pre-gap files (`00 - pregap.flac`) and corrupt FLAC headers have `audioHeader.trackLength == 0`. Result: clients see "0:00" duration tracks that can't be played and pollute album track lists. Fix: scanner now `return`s before inserting when `durationMs == null || durationMs <= 0L`. **Stale rows must be deleted manually after deploying the scanner change** — the scanner's existing-row branch only repairs linkage, not duration, so old zero-duration rows persist forever. Cleanup SQL:

  ```sql
  DELETE FROM core_v2_library.entities WHERE id IN (
    SELECT entity_id FROM core_v2_library.audio_tracks WHERE duration_ms IS NULL OR duration_ms = 0
  );
  ```

  CASCADE on `audio_tracks.entity_id_fkey` handles the audio_tracks row. Cross-schema refs (`play_history.item_id`, `favorites.track_id`, `playlist_tracks.track_id`, `track_features.track_id`, `karaoke.assets.track_id`) are by-value with no FK and may dangle — for `play_history` only (12 rows on 2026-05-17), this is acceptable.

## SonarCloud (project-specific)

- yay-tsa uses **SonarCloud Automatic Analysis** (no `sonar-scanner` step in CI) — `sonar-project.properties` is **ignored**. Exclusions must be set via web UI or API.
- `yay-tsa-v2/` subtree must stay excluded — Kotlin rewrite with own detekt config; mixing adds ~167 Kotlin issues that mask real findings and force `new_security_rating=3`.

## Streaming relative-path bug + dedup follow-on (2026-05-16)

- `JpaLibraryQueryPort.resolveTrackFilePath` originally returned `entity.sourcePath` raw. v1-ETL rows had `/media/...` absolute paths and worked; v2-scanner rows had `Artist/Album/file.flac` relative paths and resolved against the JVM CWD → `Files.exists` false → `/Audio/{id}/stream` 404, browser → `MEDIA_ELEMENT_ERROR: Format error`. Fix: when `sourcePath` does not start with `/`, prefix it with `entity.libraryRoot` before returning.
- The lesson generalises: **any code that does `Path.of(entity.sourcePath)`** (image controller too) must consider relative vs absolute. v2 scanner inserts relative, v1 ETL inserted absolute.
- `streamAudio` write loop now wraps `out.write()` in `try { ... } catch (IOException)` and logs at DEBUG. Without this every Pause/Skip surfaces as `AsyncRequestNotUsableException` → "Unhandled exception" ERROR in `GlobalExceptionHandler`. Pair the handler with a no-body `@ExceptionHandler(AsyncRequestNotUsableException)` returning `null` so async dispatch swallows it silently.
- `MultipartException` from non-multipart bodies hitting `JellyfinTracksUploadController` was logged as "Unhandled exception" → fix by adding an explicit `@ExceptionHandler(MultipartException)` mapping to 400.

## /Items pagination — TotalRecordCount must be a DB count, not `items.size`

- `JellyfinItemsController` used `ItemsResult(items, items.size, startIndex)` for every paged branch. The PWA's infinite-scroll stops as soon as `loaded >= totalRecordCount`, so a 9k-track library reported "Showing 50 of 50 songs" with no way to scroll. Add `countTracks/countAlbums/countArtists` to `LibraryQueryPort` (backed by `JpaRepository.countByEntityType`) and pass the real value into the response.
- Sanity smoke after any change to `/Items`: `curl -s "<base>/api/Items?IncludeItemTypes=Audio&Recursive=true&Limit=1" -H "Authorization: Bearer ..." | jq .TotalRecordCount` must equal the row count from `SELECT count(*) FROM core_v2_library.entities WHERE entity_type='TRACK'`.

## v1/v2 dedup — duplicate track keys per album, with cross-schema refs

- After v1→v2 cutover the library held `(album_id, track_number, disc_number)` groups with two rows: one absolute-path (v1 ETL), one relative-path (v2 scanner). Every album rendered as `2 × track_count`. Dedup script: `yay-tsa-v2/scripts/dedup-tracks.sql`. Preserved 217/217 favorites and 2,868/2,868 play_history rows; track_features lost 41 redundant duplicates (keep row already had its own features).
- PK-safe pattern for `track_features` / `karaoke.assets` (PK `track_id uuid`): single `INSERT INTO ... SELECT DISTINCT ON (dp.keep_id) ... ON CONFLICT (track_id) DO NOTHING` to transfer at most ONE drop's row per keep, then `DELETE` all drops. A loop-style `UPDATE ... WHERE NOT EXISTS` runs against the pre-statement snapshot — both UPDATEs see "keep has nothing" and the second one violates PK.
- PK-safe pattern for `user_track_affinity` (PK `(user_id, track_id)`, counter columns): pre-aggregate per `(user_id, keep_id)` with SUM/MAX, then `INSERT ... ON CONFLICT DO UPDATE` to merge counters into the keep row. Avoids the same row-by-row UPSERT race.
- Tracks with `track_number IS NULL` (no ID3 tag) cannot be deduped by this triple — they remain visible separately but are not actual file duplicates (each row corresponds to one filesystem path).

## Orphan album image rows after disk reorganisation

- 23 albums had `is_primary=true` image rows pointing at `/media/{Artist}/YYYY-MM-DD  {Album}/cover.jpg` while the actual folders on disk were renamed to `/media/{Artist}/YYYY - {Album}/`. The v2 scanner only walks audio files and never inserts image rows, so those stale image records lingered as 404s with no chance of replacement. Resolution: `DELETE FROM core_v2_library.images WHERE is_primary AND path ~ '/[0-9]{4}-[0-9]{2}-[0-9]{2}'`. Long-term fix needs scanner to discover `cover.jpg`/`folder.jpg` inside album directories.

## Hibernate `ddl-auto: update` in prod is a foot-gun

- `application-prod.yml` previously had `ddl-auto: update`. On every boot Hibernate tried to ALTER `core_v2_ml.taste_profiles.summary_text` from TEXT to varchar(255), which fails when actual rows exceed 255 chars and races with prepared-statement caches (`ERROR: cached plan must not change result type`). Set to `validate` and own DDL via Flyway, matching what `application.yml` already does outside of prod profile.

## v1 → v2 Cutover (historical, but informative for similar migrations)

### ResourceRegion + ResponseEntity<\*> erases type → 500 on Range requests

Range request to `/Audio/{id}/stream` returns `HttpMessageNotWritableException: No converter for [class ResourceRegion] with preset Content-Type 'audio/flac'` **even when** Content-Type is correct. Root cause: Kotlin `ResponseEntity<*>` erases to `ResponseEntity<Any>` at runtime; `HttpEntityMethodProcessor` invokes `canWrite(type=Object, clazz=Object, mediaType=audio/flac)`; `ResourceRegionHttpMessageConverter` checks `ResourceRegion.class.isAssignableFrom(clazz)` against `Object` — returns false. Full-body branch works by luck.

Fix shape: stop going through Spring's message-converter pipeline for binary streaming. Take `HttpServletResponse` as a controller parameter and write bytes directly. Lesson: any `ResponseEntity<*>` (Kotlin) or `ResponseEntity<?>` (Java) for binary payloads is silently dangerous — parameterize or stream directly.

### Audio streaming 500s on unrecognized codec strings

`JellyfinMediaController.streamAudio` historically did `when (track.codec?.lowercase()) { "flac" -> "audio/flac"; … else -> "application/octet-stream" }`. `audio_tracks.codec` in production is `"FLAC 16 bits"` (codec name + bit-depth), not bare tag — falls through to `application/octet-stream` → converter can't encode → `HttpMessageNotWritableException`. Browser aborts `<audio>` with `MEDIA_ELEMENT_ERROR: Format error` — looks like frontend bug but is server-side.

Fix: substring matching (`.contains("flac")`/`.contains("opus")`) AND fall back to file-extension sniffing, with `audio/mpeg` as generic last-resort instead of `application/octet-stream` — the converter exists for any `audio/*`.

### Audio streaming 500 from frontend nginx — `set $backend` after `rewrite … break`

Distinct from the codec 500 above: this one is an **nginx config** bug, not backend. The `location ~ ^/api/Audio/[^/]+/stream` block proxies via a variable (`proxy_pass $backend;`, required for regex locations). If `set $backend …;` is placed **after** `rewrite ^/api(.*)$ $1 break;`, the `break` halts the ngx_http_rewrite_module phase and the `set` never runs → `$backend` empty → nginx logs `using uninitialized "backend" variable` + `invalid URL prefix in ""` → **500 from nginx** (HTML body, not Spring JSON). Browser shows `MEDIA_ELEMENT_ERROR: Format error` — looks like a frontend/codec bug but is the proxy. Fix: put `set $backend …;` **before** the `rewrite … break`. Signal to distinguish from the codec 500: nginx default 500 page (not a Spring error) + the two nginx error-log lines above. Other `/api/*` locations using literal `proxy_pass __BACKEND_URL__` are unaffected, so a working `/Items` with a broken `/Audio/stream` points straight here. (`infra/nginx/nginx.conf.template`, fixed 2026-05-27.)

### Images endpoint silently 404s when /media is not mounted

`/Items/{itemId}/Images/{imageType}` returns **404** (not 401) when auth passes but on-disk image file is unreachable. Reproduction: `kubectl exec <backend-pod> -- ls /media` showing OS default contents (`cdrom`, `floppy`) instead of music library. Cross-check with `SELECT path FROM core_v2_library.images WHERE is_primary=true LIMIT 3`. Crawler signal: hundreds of `404 https://yay-tsa.com/api/Items/<uuid>/Images/Primary` lines.

### Proxy chain strips non-Bearer auth methods

Between Cloudflare/Traefik and v2 backend, ONLY `Authorization: Bearer <token>` reaches the auth filter intact. `X-Emby-Token`, `X-Emby-Authorization`, and `?api_key=` query all return 401 through the proxy chain, while ALL FOUR succeed via `kubectl port-forward`. Workaround: PWA sends `Authorization: Bearer` for all `fetch()` and writes token to a `yay_token` cookie for HTML5 `<audio src>` and `EventSource` (neither can send custom headers). Backend `JellyfinAuthFilter` + `ApiTokenAuthFilter` accept the cookie as a 5th auth source.

**Lesson**: when code authenticates correctly via port-forward but fails through public URL, the bug is in the proxy chain. Test: fresh login token → hit each auth surface twice (port-forward vs public URL) → diff statuses.

### Auth filter chain — multiple filters set auth

SecurityConfig adds `ApiTokenAuthFilter`, `JellyfinAuthFilter`, `SubsonicAuthFilter` (in order). First filter to see a valid token wins — subsequent short-circuit via `if (SecurityContextHolder.getContext().authentication == null)`. If you enrich one auth class with a new field (e.g., `deviceId`), the controller may still receive the OTHER auth type. Either enrich all auth classes or extract a common interface (`DeviceBoundAuthentication`).

### Heartbeat body-less compatibility

v2 `JellyfinDevicesController.heartbeat` previously required `{deviceId, sessionId}` body. PWA's `DeviceService.heartbeat()` sends no body — every 15 s call returned 400 "Invalid request body". Fix: enrich `JellyfinAuthentication` with `deviceId` resolved from `ApiToken` at filter time, then accept `@RequestBody(required = false)` and fall back to `auth.deviceId`. Lesson: when controller field is "obviously derivable" from auth context, prefer auth-derivation over forcing client to round-trip values.

### Stale token after schema cutover

After v1 → v2 backend swap, browsers carrying old v1 access token receive 401 on every API call. Symptom: cascading 401s on `/api/Items`, `/api/Sessions/Logout`, `/api/v1/me/devices`, `/api/v1/me/devices/heartbeat`. **Not a routing or filter bug** — verify by curl-testing each auth header variant with both invalid and fresh login token. If all four return 200 with fresh token, user just needs to re-login.

### Atomic ingress flip

= same gitops commit that enables v2 ingress AND disables v1 ingress. Two-step commits leave a ~30–90 s window where both ingresses active and traefik may route to v1 by priority.

v1 frontend nginx default for `YAYTSA_BACKEND_URL` is in-namespace backend service (`yay-tsa-backend.<ns>.svc.cluster.local:8096`). When v1 backend retired, frontend `CrashLoopBackOff` on `host not found in upstream` until `config.backendUrl` set explicitly to v2's cross-namespace service.

### Post-cutover OpenAPI path

After v1 Spring Boot retirement, v2 Kotlin backend exposes OpenAPI at springdoc default `/v3/api-docs`, **not** Jellyfin's legacy `/api-docs`. Schemathesis/ZAP CI steps that hardcoded `$PROD_URL/api/api-docs` must be updated. Symptom: `curl -sf $PROD_URL/api/api-docs` returns 401 → empty file → `JSONDecodeError`. Traceback looks identical to login parse failure; check the **previous** step before suspecting QA_PASSWORD drift.

## Traefik Ingress Routing

- Traefik matches Ingress rules by router priority, not longest-path-wins. Give `/api` ingress precedence over catch-all `/` on same host: `traefik.ingress.kubernetes.io/router.priority: "10"`.
- Cross-namespace ingress backend isn't supported in K8s `Ingress` v1 (only via `ExternalName` Service). For frontend (v1 ns) + backend (v2 ns) on same host, deploy separate Ingress in each namespace, both with `host: yay-tsa.com`, different paths.
- **Subsonic adapter lives at `/rest/*`, NOT `/api/rest/*`** (controller uses class-level `@RequestMapping("/rest")`). Needs its own dedicated Ingress on the v2 backend without the `strip-api` middleware. `charts/yay-tsa-v2/templates/ingress-subsonic.yaml` gated by `ingress.subsonic.enabled` (default true). Without it, every `/rest/*` request returns the PWA `index.html` (HTTP 200, `Content-Type: text/html`) and Subsonic clients (Symfonium, Finamp, Feishin) silently appear "connected" but every endpoint returns the HTML shell. Schemathesis catches this as "API accepted schema-violating request" — the test sees 200 + HTML where 400/422 was expected.
- Each protocol-specific path under the v2 backend needs explicit Ingress allowlisting. Currently shipped: `/api` (strip-prefix middleware), `/rest` (no middleware). If new protocols are added (`/mpd-http`, `/.well-known/...`), add them to the chart, not as runtime workarounds.

## ETL leaves dup track entities (v1 relative + v2 absolute paths)

- The v1 → v2 ETL imported track rows with relative `source_path` (`Artist/2015 - Album/01 - Track.flac`). The v2 `infra-library-scanner` then walked `/media` and inserted **fresh** rows for the same files with absolute paths (`/media/Artist/2015 - Album/01 - Track.flac`). `entities_source_path_key UNIQUE` did not collapse them because the two strings differ. Result: every album showed each track twice; the relative-path row's streaming endpoint 404s (`Files.exists("Artist/...flac")` is false in a `/media`-rooted container).
- Cross-schema references to track IDs are **by-value, no FK** in 11 tables (`favorites`, `play_history`, `play_statistics`, `queue_entries`, `playlist_tracks`, `track_features`, `user_track_affinity`, `karaoke.assets`, `adaptive.adaptive_queue`, `adaptive.listening_sessions`, `adaptive.playback_signals`) — column types are mixed (text/uuid). Any dedup migration must remap all of them before dropping the `entities` row, otherwise favorites/history point at dangling IDs.
- Fix shape: `INSERT INTO dup_pairs` via `DISTINCT ON (drop_id)` from a `UNION` that prefers suffix-match (`v2.source_path = '/media/' || v1.source_path`) over fallback `(album_id, track_number, disc_number)` triple. For each cross-schema table: `UPDATE … SET col = keep WHERE col = drop AND NOT EXISTS (… already exists for same user/playlist)`, then `DELETE … WHERE col = drop` for collision losers. Finally `DELETE FROM core_v2_library.entities WHERE id IN drop_pairs` — CASCADE handles `audio_tracks`/`images`/`entity_genres`. Run as a single transaction with `SELECT 'metric:', COUNT(*)` after each step so you can validate counts before COMMIT.
- Permanent prevention belongs in `infra-library-scanner`: before INSERT, look up any existing entity by **suffix-match** of the relative portion of the path (everything after `library_root`) and UPDATE its `source_path` to the new absolute form. Without that guard, every full rescan after a library-root change recreates the dup-set.

## Feature-validation gotchas (2026-05-17 /qa pass #4)

- **Bash `UID` is a readonly shell variable.** `UID=$(cat /tmp/qa-uid.txt)` silently fails with "UID: readonly variable" and `$UID` stays `""`. Test scripts then hit `/api/Users/` (no trailing UUID) and get a different response. Always pick a non-conflicting name (`UUID_`, `MY_UID`, `USER_ID`).
- **`POST /Playlists` requires `UserId` field**, NOT optional. `{"Name":"x"}` alone returns generic `400 "Invalid request body"` without naming the missing field. Frontend sends `{Name, UserId, Ids[], IsPublic}` — full shape. Add a more specific 400 message (e.g. via `@Validated` + `MethodArgumentNotValidException` handler) so unknown clients aren't guessing.
- **`/api/livez` does not exist as a Spring route.** Health probes hit `/manage/health/liveness` and `/manage/health/readiness` (Spring Actuator) — those ARE in `requestMatchers.permitAll()` in `SecurityConfig`. The "`/livez` pattern" in workspace-CLAUDE.md is a generic resilience suggestion; yay-tsa-v2 uses Actuator paths.
- **Frontend version chip (`vmain-<sha>`) updates instantly on frontend rollout** (no service-worker stickiness in the chip itself — it reads injected `GIT_SHA`). Backend rollout lags 2–5 min after CI passes (Image Updater poll). Always check BOTH deployed images:
  - `kubectl get deployment yay-tsa-production -n yay-tsa-production` (frontend)
  - `kubectl get deployment yay-tsa-v2-production-backend -n yay-tsa-v2-production` (backend)

## Duplicate albums + artists from featured-artist tags / case-mismatch ETL

Three distinct classes of entity duplication, all surfacing as the same UI symptom (`Horus - Singles` 30x, `The Hatters - Singles` 12x, etc. on `/albums`; `SKYND` + `SKYND, Bill $Aber` + `SKYND, Jonathan Davis` on `/artists`). Discovered 2026-05-17 during `/qa` after the user pasted the rendered album list.

1. **Featured-artist combinations** — pre-`b453b32e` scanner used the full FLAC `ARTIST` tag (e.g. `"луперкаль, oxxxymiron"`) verbatim when building `album:<artistName>:<albumName>` source_path. Each featured-artist combo produced a UNIQUE source_path → a separate `core_v2_library.entities` row → 30 album entities for the same logical "Horus - Singles". Fix `b453b32e` (`primaryArtist()` splitting on `,;/&|feat|ft|featuring|vs`) prevents new rows; cleanup of legacy rows is one-shot SQL.
2. **Case-mismatch artists (Cyrillic)** — v1→v2 ETL inserted `entities.source_path = 'artist:Кино'` (capitalized). v2 scanner builds `'artist:' + name.lowercase()` → `'artist:кино'` (lowercase). `findBySourcePath` lookup misses → scanner creates a sibling artist entity. All affected: `Агата Кристи, Кино, Король и Шут, Кукрыниксы, Пневмослон, Рабфак, Сатанакозёл, Сектор Газа` (8 pairs).
3. **Compound-artist entities still present after dedup** — `SKYND, Bill $Aber` and `XACV SQUAD feat Nekto BZ` survived because they were legitimately distinct artist rows (not just compound source_paths). Folding requires applying the same `primaryArtist` regex in SQL and merging into the primary.

Scripts shipped (idempotent, transactional, with verification queries):

- `yay-tsa-v2/scripts/dedup-albums.sql` — name+artist_id grouping, remaps `audio_tracks.album_id` + `entities.parent_id` + `play_statistics.item_id` (the only cross-schema by-value album ref — checked all schemas).
- `yay-tsa-v2/scripts/dedup-artists.sql` — `LOWER(name)` grouping, prefers the canonical-lowercase-source_path keep, remaps `albums.artist_id` + `audio_tracks.album_artist_id` + `entities.parent_id`, then re-runs album dedup (collapsed parent artist may surface new album dups).
- `yay-tsa-v2/scripts/dedup-compound-artists.sql` — regex `regexp_replace(name, '\s*[,;/&]\s+.*$|\s+(feat|ft|featuring|vs|with|x)\.?\s+.*$', '', 'i')` matches Kotlin's `primaryArtist`. Only merges when a primary with the same case-insensitive name already exists.

Result: 803 albums → 594, 76 artists → 65, 0 orphans. Run all three in order; each dry-run via `sed 's/COMMIT;/ROLLBACK;/' | psql` first.

## Artist `ChildCount` was always null → frontend shows "0 albums"

`JellyfinItemsController.Artist.toBaseItem()` didn't populate `childCount`, so every artist on `/artists` showed "0 albums" even after the dedup. Fix: add `LibraryQueryPort.countAlbumsByArtistIds(Set<EntityId>) -> Map<EntityId, Int>` backed by `AlbumRepository.countAlbumsByArtistIds(@Query GROUP BY artist_id)`, pre-fetch in the controller for each batch of artists, populate `BaseItem.childCount`. Same N+1-avoidance pattern as the existing batch artist-name lookup. Frontend already reads `artist.ChildCount` — no UI change needed.

## ETL / Postgres Cutover (yay-tsa specific)

- `kubectl cp` to CNPG postgres pods fails (read-only filesystem). Use stdin: `kubectl exec -i ... -- env PGPASSWORD=$P psql -h pooler ... < script.sql`.
- `pgcrypto` extension installs in **first schema in search_path with CREATE rights** — for non-superuser app roles this is often a context-owned schema. `ALTER EXTENSION pgcrypto SET SCHEMA public` requires superuser (CNPG `enableSuperuserAccess: false`). Workaround: prepend `SET search_path TO public, <ext-schema>;` to the ETL script body.

## Untracked Test Artifacts

- Schemathesis/Hypothesis writes to `.hypothesis/` in CWD — must be in `.gitignore` at workspace root AND inside any subdirectory where pytest runs (`packages/core/`, `services/server/`).
- Crawljax leaves `com/crawljax/` Java class files in CWD when run as side-effect of autoqa container.
- One-shot QA artifacts (`openapi.json`, `ANALYSIS.md`, `login-*.png`, `qa-*.jpeg`) accumulate at repo root if not gitignored — pattern `qa-*.png` / `qa-*.jpeg` covers screenshot conventions.

## CI Investigation (project-specific)

- Search job logs with `grep -E "passed|failed|exit code"` for quick test summary.
- E2E failures listed as `test-results/<test-name>-<project>/test-failed-N.png` — project suffix (`chromium`/`mobile`) shows which platform.

## Accessibility (project-specific)

- Buttons with responsive text (`hidden sm:inline`) need explicit `aria-label` — on mobile the text is hidden. Add `aria-hidden="true"` to the visible text span to avoid duplicate announcements.

## CD Verification

- Audio separator and feature extractor images may lag behind if their code wasn't changed — compare only frontend/backend images.

## Scanner artist/album derivation + API artist exposure (2026-05-16)

- The library scanner originally read **only `FieldKey.ARTIST`** ID3 tag and fell back to the literal string `"unknown"` when missing (so album `source_path` became `album:unknown:the stage`). Many FLAC rips carry only `TITLE`/`ALBUM`/`TRACK` tags, leaving 231 tracks with `album_artist_id IS NULL` and 5 albums with `artist_id IS NULL` despite the folder hierarchy clearly identifying the artist. Fix: read `FieldKey.ALBUM_ARTIST` first (canonical for grouping), then `FieldKey.ARTIST`, then derive from the file path's first segment (`<artist>/<year - album>/<track>.flac`) when there are ≥3 segments. Strip leading `^\d{4}\s*-\s*` from the second segment to get the album name.
- The scanner originally **skipped any track whose `source_path` already existed** in `entities`. After the derivation fix, legacy rows still pointed at NULL artist/album. Solution: make `upsertTrack` idempotent — on the existing-row branch, derive artist/album from tags+path again and `COALESCE`-style update `audio_tracks.album_id`, `audio_tracks.album_artist_id`, `entities.parent_id`, and `albums.artist_id` when they are NULL but a real value is now available. Change `albumId`/`albumArtistId`/`artistId`/`parentId` JPA fields from `val` to `var` to allow this.
- Even with `album_artist_id` populated, the PWA still rendered "Unknown Artist" because **`JellyfinItemsController.Track.toBaseItem()` never set `Artists` / `ArtistItems` on the response**. Track DTOs in the v2 backend were missing the artist mapping entirely — only `Album` and `AlbumId` were emitted. Fix: extend the mapper to look up the artist via `albumArtistId`, populating `Artists: [name]` and `ArtistItems: [{Name, Id}]`. To avoid N+1 (one /Items page → 50× `getArtist`), add a batch port method `LibraryQueryPort.getEntityNamesByIds(ids: Set<EntityId>): Map<EntityId, String>` (single SQL `WHERE id IN (...)`) and pre-fetch in the controller before mapping. Also remove the existing N+1 album-name lookup the same way.
- Lesson: when investigating "Unknown Artist" in the UI, **probe the raw API response first** (`curl /api/Items?Limit=3 | jq`). If the JSON has no `Artists`/`AlbumArtist`/`ArtistItems` field at all, the bug is in the controller mapper, not in the data. The DB linkage issue and the controller-mapping issue compound — fixing only one leaves the symptom intact.

## Albums with `source_path = album:unknown:*` are real after-effects of the legacy scanner

- Old scanner inserted `"album:${artistName?.lowercase() ?: \"unknown\"}:${albumName.lowercase()}"`. The `unknown` literal in the source_path is **harmless for display** — the UI uses `entities.name` (the human-readable album name). But it means the album row's `artist_id` is `NULL`, so the album page shows "Unknown Artist". The scanner repair on existing rows is sufficient — don't try to rename the source_path key in place (would orphan cross-schema references).

## QA Session Findings (2026-05-24, third pass — Group Sync + scanner test)

- **Group Sync backend ported to v2** (was a v1 feature dropped in the cutover; frontend `GroupSyncService` was 404ing on `/v1/groups/*`). Implemented as a pragmatic vertical slice in the **app module** (`dev.yaytsa.app.groups`), not a full bounded context — ArchUnit rules are keyed to `dev.yaytsa.domain.<ctx>` packages, so a feature outside those packages doesn't trip them, avoiding new gradle modules. Schema `core_v2_groups` (V001) + `flywayGroups` bean in `FlywayConfig`. OCC via `schedule_epoch` (`UPDATE … WHERE schedule_epoch = expectedEpoch` → 409). SSE membership broadcast via in-memory `GroupEventBroadcaster` (single-replica only).
- **`/v1/time` routing**: backend serves it at `/v1/time` (added to SecurityConfig `permitAll`); the PWA `ServerClock` reaches it at **`/api/v1/time`** because `client.getServerUrl()` returns the `/api`-prefixed base and Traefik strip-prefix removes `/api`. Testing the bare `https://yay-tsa.com/v1/time` returns the PWA `index.html` (frontend nginx) — always test backend `/v1/*` via the `/api/` prefix.
- **Scanner test harness (closes the long-standing `infra-library-scanner` no-tests gap):** a `@SpringBootTest` with a `ScannerTestApplication` (`@EntityScan`/`@EnableJpaRepositories` for `dev.yaytsa.persistence.library` + a `Clock` `@Bean`; its own package scan picks up `LibraryWriter`) extending `core-testkit` `AbstractPersistenceTest`, Flyway `classpath:db/library`. **Cleanup must use `TRUNCATE TABLE core_v2_library.entities CASCADE` (JdbcTemplate), NOT `repo.deleteAll()`** — row-by-row OCC deletes race the `cleanup_empty_parent` trigger → `StaleObjectStateException`. FLAC fixtures: `ffmpeg -f lavfi -i anullsrc=r=44100:cl=stereo -t <sec> -c:a flac` checked into `src/test/resources/fixtures/` (CI runners lack ffmpeg; jaudiotagger only reads STREAMINFO+tags, so silent FLACs suffice; set tags in-test via `AudioFileIO.read`/`write`).
- **Group endpoint robustness** (verified live): bad UUID path var → 400 (IllegalArgumentException handler), absent group → 404, empty/ malformed body → 400, no-auth → 401, zero 5xx — schemathesis-safe.

## QA Session Findings (2026-05-25)

- **ML feature extraction runs as a batch CronJob on the essentia image, NOT in-backend.** The `infra-ml-worker` in-pod path (`ML_ENABLED`/`ML_EXTRACTOR_SCRIPT`) stays disabled (would bloat the JVM backend image with Python+torch+Essentia → OOM risk). Instead `services/audio-ml/extractor_cli.py` (added to the `feature-extractor` essentia image) scans `core_v2_library.entities` (TRACK, `source_path` relative to `/media`) for rows missing from `core_v2_ml.track_features` and writes them directly (embeddings as pgvector `'[...]'` literals; reuses `EssentiaAnalyzer` + `DualEmbeddingExtractor`). Runs as `charts/yay-tsa/templates/feature-extractor-cronjob.yaml`, pinned to `my-server` via `nodeSelector` (hostPath `/mnt/t7-music`), DB creds via `envFrom` secret `yay-tsa-postgres`.
- **Image Updater does NOT track CronJob images** — it inspects live workload pods, and a CronJob has no steady-state pod. Tracking `feature-extractor` in the ImageUpdater CR errors / is pointless (see gitops commit "stop tracking yay-tsa feature-extractor"). Consequence: the CronJob image tag in `values.images.yaml` must be **pinned/bumped manually** to a build at/after the commit that introduced the CLI. After bumping, `kubectl annotate application yay-tsa-production -n argocd argocd.argoproj.io/refresh=normal --overwrite` to sync, then verify `kubectl get cronjob ... -o jsonpath='{.spec.jobTemplate.spec.template.spec.containers[0].image}'`.
- **Lyrics: LRCLIB fallback** (`LYRICS_LRCLIB_ENABLED`, default true, keyless). `JellyfinLyricsController` tries sidecar `.lrc` first, then fetches from lrclib.net (`/api/get` exact → `/api/search` fallback), preferring `syncedLyrics`. Response `source` is `sidecar` | `lrclib` | `none`. `LrclibClient` uses `java.net.http.HttpClient` (no new deps).
- **MPD enabled, in-cluster only** (`MPD_ENABLED=true`, ClusterIP `*-mpd:6600`). The MPD protocol is unauthenticated → no public ingress; reach via `kubectl port-forward`. Verify the daemon bound by grepping backend startup log for `MPD server listening on port 6600` (the `/dev/tcp` probe fails inside the JRE-Alpine pod — busybox `sh` lacks it; trust the log + the populated Service endpoint).
- **`services/audio-separator/` renamed to `services/audio-ml/`** (it builds both the GPU separator AND the essentia feature-extractor from one FastAPI app via `ARG VARIANT`). Image names (`audio-separator`, `feature-extractor`) were intentionally kept — each variant is correctly named; only the shared source dir was the misnomer.
- **CI `Pre-commit Checks` fails fast (~26s) on a cold cache** because `nikolay-e/pre-commit-hooks` is a **private** repo the runner can't clone anonymously (`git fetch origin --tags` → "could not read Username"). Editing `.pre-commit-config.yaml` changes the cache key → cold miss → failure. Fix: `restore-keys: [pre-commit-]` on the cache step (falls back to a prior warm cache). All hooks pass locally regardless.

## QA Session Findings (2026-05-25, ML CronJob verification)

The bounded 1-track test Job (`EXTRACT_BATCH_LIMIT=1`) caught TWO real bugs the 03:00 scheduled run would have hit — neither visible from config/lint/render, only from a live run:

- **CronJob blocked from the DB by NetworkPolicy (two layers).** "Connection refused" (RST, fast) — NOT a timeout. (1) `yay-tsa-production` has `default-deny-all`; egress to postgres:5432 was only granted to `component=backend`. (2) The shared-database `allow-app-to-pooler` ingress only admits an allowlist of `(namespace, component)` pairs — from `yay-tsa-production` only `component=backend`. Fix: label the CronJob pod `app.kubernetes.io/component: ml` (it IS the ML pipeline, matches life-as-code's convention) and add `yay-tsa-production` to `databaseAccess.ml` in the `network-policies` chart — that one list drives BOTH the namespace egress policy (`allow-database-egress-ml`) AND the pooler ingress source. A bespoke per-app egress NetworkPolicy in `charts/yay-tsa` is redundant once the shared `ml` category covers it.
- **Long-held DB connection killed by the pgbouncer pooler.** The CLI opened one connection at startup, then loaded CLAP/MERT/Essentia models (minutes) before the first write → the pooler closed the idle connection → `INSERT` failed, and `conn.rollback()` in the handler threw `InterfaceError: connection already closed`. Fix: short-lived connections — query the pending list with one connection (open→query→close), load models, then open a fresh connection per write. Never hold a connection across model load / extraction. (Same pooler-idle class as the advisory-lock note in the global skill.)
- **Verification that proved it:** `track_features` rows with `extractor_version='essentia-1.1+clap+mert'` went 0 → 1; job log `done: processed=1 failed=0`. Model load is ~slow only on a cold node (fresh `HF_HOME` emptyDir); once layers+models are cached the 1-track run is ~3 min. The real CronJob has `activeDeadlineSeconds: 21600` (6h) for the full backlog.

## QA Session Findings (2026-05-25, taste-clusters feature)

- **Per-user taste clusters → radio facet seeds.** New `core_v2_ml.taste_clusters` (read-model) written by a Python CronJob (`services/audio-ml/taste_clusters.py`, PCA→dynamic-k k-means over each user's affinity-track MERT embeddings, medoid = `representative_track_id`). Kotlin radio (`JellyfinAdaptiveController.buildRecommendedSeeds`) reads `MlQueryPort.getTasteClusterRepresentatives` as the PRIMARY seed source (affinity = cold-start fallback). Dynamic k is coverage-based (`round(N/TRACKS_PER_CLUSTER)`, min-cluster-size filter) — never hardcoded. Raw 768-dim MERT clusters as noise (high-dim); **PCA to ~85% variance first**, then k-means, is what makes facets emerge (validated: master 545 tracks → 8 coherent facets: nu-metal / death / power-metal / russian-rap / …).
- **A DB read-model written by a Python CronJob needs its Flyway migration to deploy FIRST.** The migration (`V003__taste_clusters.sql`) runs on **backend startup**, not by the job. Running the clustering job before the backend redeploys fails with `relation "core_v2_ml.taste_clusters" does not exist`. Do NOT `CREATE TABLE` manually to unblock — Flyway then fails "already exists". Sequence: push → backend image builds → image-updater → ArgoCD deploy → Flyway applies → table exists → only then run the job. Gate the job-run on `SELECT to_regclass('core_v2_ml.taste_clusters')` being non-null.
- **Adding sklearn to the essentia image** (`scikit-learn>=1.3.0` in `requirements/essentia*.txt`) — sklearn's `HDBSCAN`/`KMeans`/`PCA` are built in; no separate `hdbscan` package needed (sklearn ≥1.3). `KMeans(n_init="auto")` avoids the Pyright `n_init: str` stub false-positive while staying idiomatic.
- **"Graph Update: pip … failure"** on a Dependabot dependency-graph run is a GitHub Dependabot infra flake (`Failed to parse GITHUB_REGISTRIES_PROXY` / `record_update_job_unknown_error`), not a build gate and not caused by requirements changes — not actionable on the consumer side.
