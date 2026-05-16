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

## SonarCloud (project-specific)

- yay-tsa uses **SonarCloud Automatic Analysis** (no `sonar-scanner` step in CI) — `sonar-project.properties` is **ignored**. Exclusions must be set via web UI or API.
- `yay-tsa-v2/` subtree must stay excluded — Kotlin rewrite with own detekt config; mixing adds ~167 Kotlin issues that mask real findings and force `new_security_rating=3`.

## v1 → v2 Cutover (historical, but informative for similar migrations)

### ResourceRegion + ResponseEntity<\*> erases type → 500 on Range requests

Range request to `/Audio/{id}/stream` returns `HttpMessageNotWritableException: No converter for [class ResourceRegion] with preset Content-Type 'audio/flac'` **even when** Content-Type is correct. Root cause: Kotlin `ResponseEntity<*>` erases to `ResponseEntity<Any>` at runtime; `HttpEntityMethodProcessor` invokes `canWrite(type=Object, clazz=Object, mediaType=audio/flac)`; `ResourceRegionHttpMessageConverter` checks `ResourceRegion.class.isAssignableFrom(clazz)` against `Object` — returns false. Full-body branch works by luck.

Fix shape: stop going through Spring's message-converter pipeline for binary streaming. Take `HttpServletResponse` as a controller parameter and write bytes directly. Lesson: any `ResponseEntity<*>` (Kotlin) or `ResponseEntity<?>` (Java) for binary payloads is silently dangerous — parameterize or stream directly.

### Audio streaming 500s on unrecognized codec strings

`JellyfinMediaController.streamAudio` historically did `when (track.codec?.lowercase()) { "flac" -> "audio/flac"; … else -> "application/octet-stream" }`. `audio_tracks.codec` in production is `"FLAC 16 bits"` (codec name + bit-depth), not bare tag — falls through to `application/octet-stream` → converter can't encode → `HttpMessageNotWritableException`. Browser aborts `<audio>` with `MEDIA_ELEMENT_ERROR: Format error` — looks like frontend bug but is server-side.

Fix: substring matching (`.contains("flac")`/`.contains("opus")`) AND fall back to file-extension sniffing, with `audio/mpeg` as generic last-resort instead of `application/octet-stream` — the converter exists for any `audio/*`.

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

## ETL leaves dup track entities (v1 relative + v2 absolute paths)

- The v1 → v2 ETL imported track rows with relative `source_path` (`Artist/2015 - Album/01 - Track.flac`). The v2 `infra-library-scanner` then walked `/media` and inserted **fresh** rows for the same files with absolute paths (`/media/Artist/2015 - Album/01 - Track.flac`). `entities_source_path_key UNIQUE` did not collapse them because the two strings differ. Result: every album showed each track twice; the relative-path row's streaming endpoint 404s (`Files.exists("Artist/...flac")` is false in a `/media`-rooted container).
- Cross-schema references to track IDs are **by-value, no FK** in 11 tables (`favorites`, `play_history`, `play_statistics`, `queue_entries`, `playlist_tracks`, `track_features`, `user_track_affinity`, `karaoke.assets`, `adaptive.adaptive_queue`, `adaptive.listening_sessions`, `adaptive.playback_signals`) — column types are mixed (text/uuid). Any dedup migration must remap all of them before dropping the `entities` row, otherwise favorites/history point at dangling IDs.
- Fix shape: `INSERT INTO dup_pairs` via `DISTINCT ON (drop_id)` from a `UNION` that prefers suffix-match (`v2.source_path = '/media/' || v1.source_path`) over fallback `(album_id, track_number, disc_number)` triple. For each cross-schema table: `UPDATE … SET col = keep WHERE col = drop AND NOT EXISTS (… already exists for same user/playlist)`, then `DELETE … WHERE col = drop` for collision losers. Finally `DELETE FROM core_v2_library.entities WHERE id IN drop_pairs` — CASCADE handles `audio_tracks`/`images`/`entity_genres`. Run as a single transaction with `SELECT 'metric:', COUNT(*)` after each step so you can validate counts before COMMIT.
- Permanent prevention belongs in `infra-library-scanner`: before INSERT, look up any existing entity by **suffix-match** of the relative portion of the path (everything after `library_root`) and UPDATE its `source_path` to the new absolute form. Without that guard, every full rescan after a library-root change recreates the dup-set.

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
