# QA Methodology Learnings

## Mandatory Browser-QA User Flows (NEVER skip these)

**Why this list exists.** Health endpoints, `/Items` HTTP 200, no-pod-restart, and "the page renders" are all liveness signals — they do not prove the product works. The audio-stream 500 bug shipped despite all four being green: `actuator/health=200`, `/Items=200`, no `OOMKilled`, login + albums page rendered. The only path that catches that class of defect is clicking the actual user buttons and asserting the externally-observable outcome. Run every flow below on every QA pass, on both the desktop viewport (default) and mobile (resize to 412×915, Pixel 7). For each: 0 console errors, 0 unexpected 4xx/5xx in network panel, and the listed positive signal — not "the request returned 200".

1. **Login**
   - Fill username + password → click Sign In → URL becomes `/`.
   - `/api/Users/AuthenticateByName` returns 200; `AccessToken` is stored and the next API call carries it (`Authorization: Bearer` header).
   - Negative path: wrong password → visible error message, no token written.
2. **Library navigation**
   - `/`, `/albums`, `/artists`, `/songs`, `/favorites`, `/settings` — every link in the side/bottom nav loads its page.
   - Albums grid renders ≥10 thumbnails; verify with `Array.from(document.querySelectorAll('img')).filter(i => i.naturalWidth>50)` — counting `naturalWidth>0` alone is not enough because SVG placeholders pass that check (real album art has width >50). At least one network response to `/api/Items/<id>/Images/Primary` must be 200; if ALL of them are 404, image rendering is broken regardless of placeholder fallback.
3. **Album detail → playback start (THE single most critical flow)**
   - Open `/albums/<id>` of a track-bearing album.
   - Click first track's Play button.
   - Within 3 seconds: `<audio>` element exists, its `paused === false`, `currentTime > 0`, and **network shows `GET /api/Audio/<trackId>/stream` returning `206 Partial Content` (range request) or `200`** — anything 4xx/5xx here means streaming is broken and the user is staring at a frozen player.
   - Console must NOT contain `MEDIA_ELEMENT_ERROR`, `Failed to load audio`, or `Audio engine error`. These usually mean the backend returned a non-audio body that the HTML5 element couldn't decode (e.g. JSON error page with `Content-Type: application/octet-stream`).
   - Pause → `paused === true`, audio element stops. Resume → playback continues from same `currentTime`.
4. **Seek**
   - Move the progress bar (click 75% along the track or `audio.currentTime = audio.duration * 0.5`).
   - Within 2 seconds: `audio.currentTime` is within ±1 s of the target AND network shows a fresh range request `Range: bytes=<offset>-` returning 206. If the same byte-0 request repeats, range support is broken.
5. **Next / previous**
   - Next: player switches to next track in album order, new track's `/api/Audio/<id>/stream` fires, `currentTrack.Id` changes in the player store.
   - Previous (within first 3 s of playback): goes to actual previous track, not "restart current track". Previous (after 3 s into a track): seeks current to 0. Both behaviors are real product requirements and both must be checked.
6. **Volume + mute**
   - Volume slider changes `audio.volume`. Mute toggle sets `audio.muted=true` AND visually flips the icon. Unmute restores the previous volume, not 1.0.
7. **Shuffle + repeat**
   - Shuffle on → queue order in player store changes; turning off restores original order.
   - Repeat one → end of track auto-restarts same track. Repeat all → end of last queue item plays the first item.
8. **Favorites**
   - Click heart on a track → POST `/api/UserFavoriteItems/<id>` returns 200, icon flips to filled, and `/api/Items?IncludeItemTypes=Audio&Filters=IsFavorite` includes it.
   - Click again → DELETE returns 200, icon empties, item disappears from favorites.
9. **Search**
   - Type at least 3 chars into search box → `/api/Items?SearchTerm=…&Recursive=true` returns ≤50 items, results visible in dropdown, clicking a result navigates to its detail page.
10. **Lyrics + karaoke (where applicable to track)**
    - For a track with lyrics: open lyrics panel → see synchronized lines (the `[LYRICS_TEST_IDS.LOADING]` placeholder is acceptable in E2E for up to ~10 s, then real content). 501 on `/api/Lyrics/<id>/fetch` is acceptable only if the panel falls back gracefully — no toast spam, no exception in console.
    - For a track with karaoke stems ready: toggle karaoke mode → secondary audio element loads `/api/Karaoke/<id>/instrumental` returning 200, vocals toggle switches between `/instrumental` and `/vocals`.
11. **Queue panel**
    - Open queue → current track highlighted, future entries listed in order. Click any future entry → player jumps to it. Drag-reorder → store order updates and persists across navigation.
12. **Devices + multi-device**
    - Open Devices panel → current browser appears with the right device name. With a second tab logged in as same user, the second tab also appears within ~15 s (heartbeat interval).
    - EventSource: `new EventSource('/api/v1/me/devices/events')` opens (readyState 1) and receives a `ready` message within 5 s. SSE 500 here means the auth filter chain stripped the cookie — distinct bug class from streaming 500.
13. **Settings**
    - Theme toggle (if exposed) — does not exist on yay-tsa; verify intended dark-only behavior.
    - Library scan button — POST returns 202/200, scan progress visible in admin/log feed.
    - Logout button → token cleared from session/local storage, redirected to `/login`, subsequent `/api/Items` request returns 401.
14. **Mobile-only assertions (resize viewport to 412×915 first)**
    - Bottom tab bar visible, sidebar hidden.
    - PlayerBar mini-bar visible above tab bar; clicking opens MobileFullPlayer.
    - In MobileFullPlayer: shuffle/repeat/queue/lyrics buttons all reachable with thumb-sized hit areas (≥44 px).
    - Pull-to-refresh on library pages does not destroy player state.
15. **PWA + background tab**
    - Service worker registers (`navigator.serviceWorker.getRegistrations()` non-empty).
    - "Install" prompt available (PC) or manifest valid in DevTools Application tab.
    - Start playing → switch to another tab for 30 s → return: playback continues (or `visibilitychange` recovery kicks in and within 2 s the next track starts). Do NOT accept "audio resumes 5 minutes later".

If any of the above fails, the bug is shipped to production — `/qa` is not "complete" until every one of these is green on the deployed image whose tag matches `kubectl get deployment ... -o jsonpath='{.spec.template.spec.containers[0].image}'`.

## Don't trust smoke endpoints alone

- `actuator/health` only proves Tomcat started. It says nothing about whether `/Items` returns data, `/Audio/stream` returns audio bytes, `/Images/Primary` returns image bytes, or the JPA `EntityManager` is wired correctly. A green health probe with a broken streaming endpoint is the most common "deploy looks fine, users complain" scenario in this project.
- After every deploy, exercise at least one **read** path (`/Items` with auth → ≥1 row), one **binary** path (`/Audio/<id>/stream` with Range header → 206 with `audio/*` Content-Type), and one **write** path (favorite + unfavorite). Anything less is theatre.
- Backend log `Unhandled exception` + `HttpMessageNotWritableException` is the unambiguous signal of "server returned 5xx with body the client can't decode". Grep for it before declaring a release healthy: `kubectl logs -n yay-tsa-v2-production deployment/... --since=5m | grep -E "Unhandled|MessageNotWritable|MessageConversion"`.

## CI Investigation

- Search job logs with `grep -E "passed|failed|exit code"` for quick test summary
- E2E failures listed as `test-results/<test-name>-<project>/test-failed-N.png` — project suffix (`chromium`/`mobile`) shows which platform failed

## CD Verification

- Audio separator and feature extractor images may lag behind if their code wasn't changed — compare only frontend/backend images

## Mobile E2E Tests

- Mobile viewport tests use a separate Playwright project (`[mobile]`) with Pixel 7 dimensions (412px)
- PlayerBar renders a mini-bar on mobile (`md:hidden`) and hides it with CSS `invisible` when full player opens — `expect(playerBar.playerBar).toBeVisible()` fails while full player is open; use `expect(playerBar.currentTrackTitle).toBeVisible()` instead
- Test IDs must be present in BOTH PlayerBar mini-bar AND MobileFullPlayer for `:visible` selectors to work across states
- Modal components must use `createPortal` to escape invisible parent containers — otherwise dialogs are invisible when opened from MobileFullPlayer
- `ensureControlsVisible()` opens the full player on mobile, which changes which elements are visible — test locators using `:visible` must account for this state change
- `getByRole('button', { name: 'Shuffle' })` can match AlbumDetailPage text-button — use `locator('button[aria-label="Shuffle"][aria-pressed]:visible')` to target player controls
- Badge text on mobile may include unit suffix (e.g., "15m") — use `Number.parseInt(text ?? '0', 10)`, not `Number(text)`
- `setPointerCapture` throws `DOMException` for synthetic pointer events — guard with try/catch in `handlePointerDown`
- Monkey test 300s timeout on mobile CI is expected when iterations don't complete

## Karaoke / Audio Separator

- Stems PVC must be mounted in BOTH audio-separator AND backend deployments — separator writes stems, backend reads them
- After separation completes, `getStatus()` validates stem files exist on disk via `validateStemFilesExist()` — if backend can't see files, it resets `karaokeReady=false`
- `tryRecoverFromOrphanedFiles()` can recover DB state from existing stem files on disk without re-processing
- Stem path pattern: `/app/stems/{trackId}/{filename}_instrumental.wav` and `_vocals.wav`
- Separator health check shows model info and device (cuda/cpu) — useful for diagnosing GPU issues

## E2E Test Flakiness

- `previous()` in player store uses `engine.getCurrentTime()` which reads `audio.currentTime` — this can be stale after `seek(0)` in headless Chrome
- Fix: read from timing store (`useTimingStore.getState().currentTime`) which is updated synchronously on seek
- Timing store `updateTiming()` uses `requestAnimationFrame` batching — seek must use synchronous `seekTo()` to avoid RAF race where audio timeupdate events overwrite the pending seek value before the frame fires
- `seek()` must always update timing store even when `controller.isActive` — `pause()` uses `void controller.interrupt()` (fire-and-forget), so controller may still be active when test calls seek immediately after pause. If seek is fully skipped, timing store retains pre-seek value and `previous()` sees `currentTime > 3` → restarts track instead of going back
- After `seek(0)` in page object, wait for `audio.currentTime < 1` before proceeding — otherwise `clickPreviousAndWait` fires before seek completes and player restarts instead of going back
- `queue.advanceTo(trackId)` must skip `recordCurrentToHistory()` when target index equals current index — otherwise `commitPlaybackSideEffects` (which always calls `advanceTo` after load) pushes the just-played track to history. Then `previous()` pops that same track and goes nowhere. Symptom: pause + seek(0) + previous resumes the same track instead of going back. Same guard applies to `jumpTo(index)` for self-jumps.
- Lyrics test must also accept loading state (`LYRICS_TEST_IDS.LOADING`) — in E2E environments lyrics fetch to external services may take longer than 10s
- Lighthouse LCP budget of 2500ms is too tight for GitHub Actions runners — 3000ms is more realistic for CI

## Multi-Device Sync

- SSE hook `useDeviceEvents` must fetch device list on mount — otherwise `updateDeviceState` maps over empty array and drops all events
- When SSE event arrives for unknown `deviceId`, refetch full device list to pick up new devices
- Backend `broadcastDeviceState` should include `nowPlayingItemName` alongside `nowPlayingItemId` — otherwise DevicesPanel shows position but no track name
- Crawler timeout on SSE-heavy pages (`networkidle` never fires) is expected — not a real broken link

## API Client Tests

- `MediaServerClient` sends `Authorization: Bearer <token>` for the actual auth credential, and `X-Emby-Authorization: MediaBrowser Client="…", Device="…", …` purely as client-identity metadata (no token in it). Tests must assert against `Authorization: Bearer` for the token and against `X-Emby-Authorization` only for `Client="…"` / `Device="…"` / `Version="…"` substrings — never expect `Token="…"` in `X-Emby-Authorization`. Proxy chain strips non-Bearer auth, so the `Bearer` header is the only one that survives end-to-end

## Accessibility

- Buttons with responsive text (`hidden sm:inline`) need explicit `aria-label` — on mobile the text is hidden, leaving the button without accessible name
- Add `aria-hidden="true"` to the visible text span to avoid duplicate announcements

## Background Tab Playback

- Browser throttles `setTimeout`/`setInterval` in background tabs (Chrome: 1s minimum, frozen tabs: paused entirely)
- `AudioContext` gets suspended in background — `linearRampToValueAtTime` stops processing, `currentTime` freezes
- `audioContext.resume()` may reject in background tabs without user gesture — must not block `HTMLAudioElement.play()`
- Fix: fire-and-forget `audioContext.resume()` (don't await), let `visibilitychange` handler recover on foreground
- Recovery pattern: on `visibilitychange` to `visible`, check if `isPlaying && paused && ended` → force advance to next track via `controller.interrupt`
- `HTMLAudioElement.play()` works in background tabs if user previously initiated playback with a gesture
- `transitionToPreloaded()` and `seamlessSwitch()` must also attempt AudioContext resume before secondary element play

## Backend OOM / Playback Stability

- Backend OOMKilled at 2Gi limit with JVM heap 1536m — idle consumption ~1112Mi leaves no room for burst image processing
- Concurrent `/Images/Primary` requests (50+) each load full image into memory, decode to BufferedImage, resize, and convert to WebP — can exhaust heap rapidly
- Fix: limit concurrent image processing with semaphore (MAX_CONCURRENT_IMAGE_PROCESSING=4), increase backend memory limit to 3Gi
- Audio separator OOMKilled repeatedly (27 restarts) at 4Gi limit with BS-Roformer model — needs 6Gi
- Karaoke backfill retries failed tracks indefinitely — add `karaoke_fail_count` column and skip tracks after 3 failures
- Entrypoint computes JVM heap as 75% of container limit — increasing container limit automatically increases heap
- When backend OOMKills, all active stream connections break mid-playback — this is the "sometimes plays, sometimes doesn't" symptom

## Post-Deploy QA

- Wait-for-rollout step must fail on version mismatch (not just warn) — otherwise Schemathesis/ZAP/crawler run against old version and all fail on auth
- Wait-for-rollout MUST keep its auth check (don't strip it). The auth round-trip is itself a deeper liveness signal: it proves DB connectivity, the auth filter, and Bearer token issuance — all things that "container started" doesn't guarantee. If you find yourself wanting to strip auth, the real bug is that auth-failure and rollout-not-ready both manifest as `VERSION=''`. Fix: distinguish auth failure (credential/user problem → fail fast, do NOT keep retrying for 10min) from version-mismatch (rollout in progress → keep polling). Track an auth-failure counter and bail with a useful error after 3-4 attempts naming the username and HTTP status.
- Build scripts (e.g., `generate-icons.js`) need `/* eslint-disable no-console */` — ESLint no-console rule applies to all JS files including build utilities
- `eslint-plugin-boundaries` v5→v6 migration pending: `element-types` renamed to `dependencies`, `no-private` deprecated — warnings only, not blocking
- **"Backend version mismatch after 10min: got '', expected 'main-<sha>'" has two distinct root causes:**
  1. Argo CD Image Updater is failing to write back to git (deployment never gets the new tag). Check `kubectl logs -n argocd -l app.kubernetes.io/name=argocd-image-updater --since=15m | grep yay-tsa`. A common cause: an annotation lists an image alias whose `helm.image-tag` parameter resolves to a key that the rendered chart does not produce (e.g., a service that is `enabled: false` in values.prod.yaml). Image Updater errors "parameter not found" and aborts the entire batch — backend/frontend updates roll back too. Fix: drop the disabled service from BOTH the `argocd-image-updater.argoproj.io/image-list` annotation in the gitops Application manifest AND the `ImageUpdater` CR's `applicationRefs[].images[]` list (they're separate sources of truth in newer ArgoCD Image Updater versions).
  2. The QA test user (`vars.QA_USERNAME`, default `test`) doesn't exist in the production DB, or `secrets.QA_PASSWORD` is stale. The script silently treats auth failure as `VERSION=''`, which looks identical to a slow rollout. Verify with `kubectl exec -n shared-database <primary-pod> -c postgres -- psql -U postgres -d yaytsa_production -c "SELECT username, is_active FROM users WHERE username='test';"`. If absent, create with: random password (24+ chars), bcrypt hash (rounds≥12), `INSERT` via the **primary** pod (find it with `kubectl get cluster -n shared-database -o jsonpath='{.items[0].status.targetPrimary}'` — replicas reject INSERTs as "read-only transaction"). Then `gh secret set QA_PASSWORD --repo nikolay-e/yay-tsa` to the same plaintext; also stash in Keychain as `yay-tsa-qa-password`.
- Schemathesis with `--checks all` against the live backend takes ~22min total: Examples (instant) + Coverage (~6min, ~109 ops) + Fuzzing (~16min, ~108 ops). Stateful phase adds more on top. Plus ZAP + crawler: budget ≥60min for the whole `post-deploy-qa` job (`timeout-minutes` was 30 originally → silently truncated the run as `##[error]The operation was canceled.` mid-Stateful, with no upload-artifact and no per-step error). Watch for the discrepancy between Schemathesis printing its own summary line and the job not getting to the `Run ZAP` group — that's the timeout signature.

## SonarCloud

- yay-tsa uses **SonarCloud Automatic Analysis** (no `sonar-scanner` step in CI) — `sonar-project.properties` is **ignored** in this mode. Exclusions must be set via the SonarCloud web UI or API: `POST /api/settings/set?key=sonar.exclusions&values=...&values=...&component=<key>` (Bearer auth, repeat `values=` for multi-value lists; comma-separated single string is stored as one element and won't match)
- `yay-tsa-v2/` subtree must stay excluded — Kotlin rewrite with its own detekt config; mixing it into the main gate adds ~167 Kotlin issues (S1192/S6508/S6619/S117/S108…) that mask real findings and force `new_security_rating=3`
- `plsql:VarcharUsageCheck` and `plsql:OrderByExplicitAscCheck` on Flyway migrations are PostgreSQL false positives — kept in `sonar-project.properties` as documentation but the active suppression is via the web-UI rule-disable / project settings
- `typescript:S7741` "Compare with `undefined` directly instead of using `typeof`" — use `if (globalThis.window !== undefined)`, not `if (typeof globalThis.window !== 'undefined')`. Standard ESLint/TS rules previously preferred `typeof` to dodge ReferenceError on truly undeclared globals; on `globalThis.window` (a property access, not a bare identifier) `=== undefined` is safe and Sonar requires it

## ESLint / Pre-commit

- `eslint --max-warnings=0` is enforced in pre-commit — `import/order` warnings fail CI (the hook auto-fixes locally and exits 1 on "files were modified", so the auto-fix never lands in CI)
- `boundaries/element-types` deprecation warnings printed to stderr by `eslint-plugin-boundaries` v5 are not findings — they're plugin migration noise; ESLint exit code is what matters
- After adding a new component that imports both `@/` aliases and relative paths, run `npx eslint <file>` before commit — Prettier alone won't catch import-order violations
- **CI uses `npm ci` (lockfile-pinned); local `node_modules` may drift to a higher major.** Always check `npm ls eslint --depth=0` before debugging "passes locally, fails CI". `invalid` next to the version means run `npm ci` to sync. ESLint 10 vs 9 differed on `@typescript-eslint/no-unnecessary-type-assertion` auto-fix (10 left `as Record<string, unknown>` casts, 9 removed them) — pre-commit reported "files were modified by this hook" only in CI
- When pre-commit reports "files were modified by this hook" but no rule findings are printed, run `git diff` after `pre-commit run --all-files` to see the auto-fix payload — that's the fix to commit

## Untracked Test Artifacts

- Schemathesis/Hypothesis writes to `.hypothesis/` in CWD — must be in `.gitignore` at workspace root and inside any subdirectory where pytest runs (`packages/core/`, `services/server/`)
- Crawljax leaves `com/crawljax/` Java class files in CWD when run as side-effect of autoqa container
- One-shot QA artifacts (`openapi.json`, `ANALYSIS.md`, `login-*.png`, `qa-*.jpeg`) accumulate at repo root if not gitignored — pattern `qa-*.png` / `qa-*.jpeg` covers screenshot conventions

## globalThis.window

- `typescript:S7764` flags bare `window` references — prefer `globalThis.window` for SSR-safe checks. Same rule applies to `document`, `navigator`, `location`

## Helm Chart Versioning

- Pre-release identifiers in semver sort **lexicographically**: random commit SHAs after a `-main.` prefix order non-chronologically. Example: `0.5.0-main.g0c3b64d` < `0.5.0-main.ge2733e1` because `'0' < 'e'`. ArgoCD with `targetRevision: ">=X-0"` then picks an OLDER chart and apparent gitops changes never propagate. Fix: prefix dev versions with the padded GitHub run number — `${BASE_VERSION}-main.r$(printf '%010d' $GITHUB_RUN_NUMBER)-g${SHORT_SHA}` — so each new build always sorts higher regardless of SHA.
- Semver pre-release numeric identifiers must not start with 0 → use a leading `g` (git-style) on the SHA component so SHAs like `0531376` become `g0531376` and pass `helm package` validation.
- Stray non-dev chart releases (e.g. `1-final-before-retirement` pushed during a v1 wind-down) sort **higher than any `0.1.x-*` pre-release** because Helm parses `1` as `1.0.0` and `1.0.0 > 0.1.0`. With an unbounded `targetRevision: ">=0.1.0-0"`, ArgoCD silently pins to the stray chart and every subsequent fix in `templates/` never reaches prod (newer `0.1.x-main.r…` charts get ignored). Symptom: gitops chart sources look right, gh-pages lists the new chart, but `kubectl get application <app> -n argocd -o yaml | grep revisions:` shows the stray version. Fix: in the gitops `Application` manifest, bound the range — `targetRevision: ">=0.1.0-0,<0.2.0-0"` — so prod tracks the intentional dev train. Optional cleanup: delete the bogus tarball from `gh-pages` and rerun `helm repo index`.

## Helm Template Conditionals

- `default true .Values.x` returns `true` when `.Values.x` is **explicitly `false`** — Helm's `default` treats `false` as empty. To gate "default on, off when explicitly disabled", use `(or (not (hasKey .Values "x")) .Values.x)` instead.
- A pwa-ingress / "static asset" sub-chart in v1 had hardcoded `/api/Users/AuthenticateByName` and `/api/System/Info/Public` Exact paths bypassing oauth2-proxy — routed to v1 backend. When retiring v1, these MUST also be gated off (or the whole pwa-ingress template guarded) — disabling the dedicated api-ingress alone leaves them dangling.

## Traefik Ingress Routing

- Traefik matches Ingress rules by router priority, not longest-path-wins. To give a `/api` ingress precedence over a catch-all `/` ingress on the same host, add `traefik.ingress.kubernetes.io/router.priority: "10"` to the more-specific ingress (any positive integer; default 0).

## ETL / Postgres Cutover

- `kubectl cp` to CNPG postgres pods fails (read-only filesystem). Use stdin: `kubectl exec -i ... -- env PGPASSWORD=$P psql -h pooler ... < script.sql`.
- `pgcrypto` extension is installed in the **first schema in search_path with CREATE rights** — for non-superuser app roles this is often a context-owned schema (e.g. `core_v2_playlists`). `ALTER EXTENSION pgcrypto SET SCHEMA public` requires superuser (CNPG has `enableSuperuserAccess: false`). Workaround: prepend `SET search_path TO public, <ext-schema>;` to the ETL script body.
- Hibernate `spring.jpa.hibernate.ddl-auto: update` in prod silently re-ALTERs `TEXT` columns to `VARCHAR(255)` (default JPA String length). Source rows exceeding 255 chars then fail ETL with "value too long for type character varying(255)". Set to `validate` in prod and own all DDL via Flyway; backfill an ALTER migration if Hibernate already corrupted the schema.
- Cross-namespace ingress backend isn't supported in K8s `Ingress` v1 (only via `ExternalName` Service). To split frontend (v1 namespace) from backend (v2 namespace) on the same host, deploy a separate Ingress in each namespace, both with `host: yay-tsa.com`, different paths — traefik aggregates them.

## v1 → v2 cutover

- Atomic ingress flip = same gitops commit that enables `v2 ingress` AND disables `v1 ingress` (via apiIngressEnabled / equivalent). Two-step commits leave a ~30-90s window where both ingresses are active and traefik may route to v1 by priority.
- v1 frontend nginx default for `YAYTSA_BACKEND_URL` is the in-namespace backend service (`yay-tsa-backend.<ns>.svc.cluster.local:8096`). When v1 backend is retired, the frontend CrashLoopBackOff on `host not found in upstream` until you set `config.backendUrl` explicitly to v2's cross-namespace service (`http://yay-tsa-v2-production-backend.yay-tsa-v2-production.svc.cluster.local:80`).

## Post-cutover OpenAPI path

- After v1 Spring Boot retirement, the v2 Kotlin backend exposes OpenAPI at the springdoc default `/v3/api-docs`, **not** Jellyfin's legacy `/api-docs`. Schemathesis/ZAP CI steps that hardcoded `$PROD_URL/api/api-docs` must be updated to `$PROD_URL/api/v3/api-docs`. Symptom: `curl -sf $PROD_URL/api/api-docs` returns 401 (auth-protected, since `/api-docs` is not a permitAll path) → file ends up empty → next `python3 -c "...json.load..."` line fails with `JSONDecodeError: Expecting value: line 1 column 1 (char 0)`. The traceback looks identical to a login parse failure; check the **previous** step (api-docs fetch) before suspecting QA_PASSWORD drift.

## Stale token after schema cutover

- After v1 → v2 backend swap, browsers carrying the old v1 access token will receive 401 on every API call (the token is unknown to the v2 `users.api_tokens` table). Symptom in browser: cascading 401s on `/api/Items`, `/api/Sessions/Logout`, `/api/v1/me/devices`, `/api/v1/me/devices/heartbeat`. Not a routing or filter bug — verify by curl-testing each auth header variant (`Authorization: Bearer`, `X-Emby-Token`, `X-Emby-Authorization: MediaBrowser Token="..."`, `?api_key=`) against `/api/Items` with both an invalid token (expect 401) and a fresh login token (expect 200). If all four return 200 with a fresh token, the system is fine — the user just needs to re-login.

## Heartbeat body-less compatibility

- v2 `JellyfinDevicesController.heartbeat` previously required `{deviceId, sessionId}` body. The PWA's `DeviceService.heartbeat()` sends no body — every 15s call returned 400 "Invalid request body" and `/v1/me/devices` stayed empty (no projection registration ever). Fix: enrich `JellyfinAuthentication` with `deviceId` resolved from the ApiToken at filter time, then accept `@RequestBody(required = false)` and fall back to `auth.deviceId` (sessionId defaults to deviceId — `SessionInfo.id` from login response is ephemeral, never persisted). Lesson: when a controller field is "obviously derivable" from auth context, prefer auth-derivation over forcing the client to round-trip values it received on login and may not have persisted.

## ArgoCD `Application.spec` can lag gitops even when root-app is `Synced`

- `gitops-root` (the app-of-apps Application) can report `Synced` to a gitops commit whose `kubernetes/argocd/applications/<app>.yaml` _should_ mutate a child Application's `spec.sources[0].targetRevision`, yet `kubectl get application <app> -n argocd -o jsonpath='{.spec.sources[0].targetRevision}'` still shows the old value. The root app's last `operationState.finishedAt` may pre-date the child-app edit, and a `argocd.argoproj.io/refresh=hard` annotation on the root app does **not** reliably re-evaluate manifests for unchanged-by-default child specs (server-side apply field ownership keeps the cluster-side value).
- Workaround: `kubectl apply -f kubernetes/argocd/applications/<app>.yaml -n argocd` directly to force-propagate the new spec, then `kubectl annotate application <app> ... refresh=hard` on the child to re-resolve `targetRevision` chart selection. Verify with the jsonpath query above — only treat the spec as updated when the live `targetRevision` literally matches the file in git.

## ImageUpdater CR-mode vs annotations

- argocd-image-updater in this cluster runs in CR-mode: the `kind: ImageUpdater` CR in `kubernetes/argocd/argocd-image-updater/image-updaters.yaml` is the authoritative source, NOT the `argocd-image-updater.argoproj.io/*` annotations on the `Application` (those are silently ignored). A new app that only ships annotations gets zero image reconciles. Symptom: deployment image tag never advances even though new images are pushed to GHCR and the Application is `Synced+Healthy`.
- The Application that owns ImageUpdater CRs has `ignoreDifferences` for `argocd-image-updater.argoproj.io/ImageUpdater` covering `jqPathExpressions: [".spec", ".status"]` — by design, since the controller mutates its own CR. ArgoCD will therefore NOT apply spec changes from git on its normal sync cycle. Force changes via `kubectl apply -f image-updaters.yaml` after pushing to gitops.

## Auth filter chain — multiple filters set auth

- SecurityConfig adds `ApiTokenAuthFilter`, `JellyfinAuthFilter`, and `SubsonicAuthFilter` (in that order) before `UsernamePasswordAuthenticationFilter`. The first filter to see a valid token wins — subsequent filters short-circuit via `if (SecurityContextHolder.getContext().authentication == null)`. If you enrich one auth class with a new field (e.g., `deviceId`), the controller may still receive the OTHER auth type if its filter ran first. Either enrich all auth classes or extract a common interface (e.g., `DeviceBoundAuthentication`) implemented by each, then `as? DeviceBoundAuthentication` in the controller.

## ResourceRegion + ResponseEntity<\*> erases type → 500 on Range requests

- Range request to `/Audio/{id}/stream` returns `HttpMessageNotWritableException: No converter for [class org.springframework.core.io.support.ResourceRegion] with preset Content-Type 'audio/flac'` **even when** the Content-Type is the correct `audio/flac`. Root cause: a Kotlin `ResponseEntity<*>` return type erases to `ResponseEntity<Any>` at runtime; `HttpEntityMethodProcessor` invokes `canWrite(type=Object, clazz=Object, mediaType=audio/flac)` on each converter, and `ResourceRegionHttpMessageConverter` checks `ResourceRegion.class.isAssignableFrom(clazz)` against `Object` — returns false. The full-body branch (`ResponseEntity.body(Resource)`) works by luck because `ResourceHttpMessageConverter.canWrite` also accepts `Object` for compatible media types; the range branch does not.
- Fix shape: stop going through Spring's message-converter pipeline for binary streaming. Take `HttpServletResponse` as a controller parameter and write bytes directly: `FileChannel.open(...).position(start)`, then `Channels.newInputStream(channel).copyTo(response.outputStream)`. This also gives clean handling of 416 (Range Not Satisfiable) and skips the entire ResourceRegion/converter dance.
- Lesson: any time you see `ResponseEntity<*>` (Kotlin) or `ResponseEntity<?>` (Java) for binary payloads, suspect this exact failure. Either parameterize the return type (`ResponseEntity<ResourceRegion>` / `ResponseEntity<Resource>`) or stream directly. The `<*>` form is silently dangerous because compile-time it looks fine and runtime it depends on the specific converter's tolerance for `Object`.

## Audio streaming 500s on unrecognized codec strings

- `JellyfinMediaController.streamAudio` historically did `when (track.codec?.lowercase()) { "flac" -> "audio/flac"; … else -> "application/octet-stream" }`. `audio_tracks.codec` in production is populated by the scanner with strings like `"FLAC 16 bits"` (codec name + bit-depth annotation), not the bare codec tag — the `when` falls through to `application/octet-stream`, then Spring's `ResourceRegionHttpMessageConverter` cannot encode a `ResourceRegion` with that media type and throws `HttpMessageNotWritableException: No converter for [class org.springframework.core.io.support.ResourceRegion] with preset Content-Type 'application/octet-stream'`. The browser then aborts the `<audio>` element with `MEDIA_ELEMENT_ERROR: Format error` — symptom looks like a frontend bug but is purely server-side.
- Fix shape: do substring matching (`.contains("flac")`/`.contains("opus")` etc.) on the codec hint AND fall back to file-extension sniffing, with `audio/mpeg` as a generic last-resort instead of `application/octet-stream` — the converter exists for any `audio/*` type.
- Catch this earlier: any time `/api/Audio/{id}/stream` returns a 5xx, grep `kubectl logs ... | grep "Audio.*stream\\|HttpMessageNotWritableException"` first — the exception class names this exact failure mode unambiguously.

## Images endpoint silently 404s when /media is not mounted

- `/Items/{itemId}/Images/{imageType}` returns **404** (not 401) when auth passes but the on-disk image file is unreachable. `JpaLibraryQueryPort.getPrimaryImage` reads `path = /media/...` from `core_v2_library.images`; the controller then checks `Files.exists(filePath)` and returns 404 on miss. If the chart didn't mount the music library hostPath (`backend.media.enabled: false` or — see Helm Chart Versioning — ArgoCD pinned to an older chart that lacks the mount template), every album/artist image 404s in production even though album JSON includes `ImageTags.Primary`.
- Reproduction: `kubectl exec <backend-pod> -- ls /media` showing the OS default contents (`cdrom`, `floppy`) instead of the music library is the unambiguous signal. Cross-check against `SELECT path FROM core_v2_library.images WHERE is_primary=true LIMIT 3` from the CNPG primary — if those paths are correct and the file system isn't, the bug is on the deployment side, not the data side.
- Crawler signal: hundreds of `404 https://yay-tsa.com/api/Items/<uuid>/Images/Primary?...` lines from `tools/frontend-crawler/crawl.js` against the `/albums` and `/artists` routes — a clear and quick distinguisher from the 401-cascade-after-token-rotation pattern.

## Proxy chain strips non-Bearer auth methods

- Between Cloudflare/Traefik and the v2 backend, ONLY `Authorization: Bearer <token>` reaches the auth filter intact. `X-Emby-Token`, `X-Emby-Authorization: MediaBrowser Token="..."`, and `?api_key=<token>` query all return 401 through the proxy chain, while ALL FOUR succeed when hitting the pod directly via `kubectl port-forward`. Root cause not identified — Traefik middleware (`strip-api` only strips `/api` prefix) and Cloudflare don't show explicit header/query filtering. Workaround shipped: PWA sends `Authorization: Bearer` for all `fetch()` calls and writes the token to a `yay_token` cookie for HTML5 `<audio src>` and `EventSource` (neither can send custom headers). Backend `JellyfinAuthFilter` + `ApiTokenAuthFilter` accept the cookie as a 5th auth source.
- Lesson: when the same code authenticates correctly via port-forward but fails through the public URL, the bug is in the proxy chain, not the code. Test step: pull a fresh login token → hit each auth surface twice (port-forward vs public URL) → diff statuses to localize.
