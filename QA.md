# QA Methodology Learnings

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

- `MediaServerClient` uses `X-Emby-Authorization` header (Jellyfin wire format), not `Authorization: Bearer` — tests must assert against `X-Emby-Authorization`

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

## ImageUpdater CR-mode vs annotations

- argocd-image-updater in this cluster runs in CR-mode: the `kind: ImageUpdater` CR in `kubernetes/argocd/argocd-image-updater/image-updaters.yaml` is the authoritative source, NOT the `argocd-image-updater.argoproj.io/*` annotations on the `Application` (those are silently ignored). A new app that only ships annotations gets zero image reconciles. Symptom: deployment image tag never advances even though new images are pushed to GHCR and the Application is `Synced+Healthy`.
- The Application that owns ImageUpdater CRs has `ignoreDifferences` for `argocd-image-updater.argoproj.io/ImageUpdater` covering `jqPathExpressions: [".spec", ".status"]` — by design, since the controller mutates its own CR. ArgoCD will therefore NOT apply spec changes from git on its normal sync cycle. Force changes via `kubectl apply -f image-updaters.yaml` after pushing to gitops.

## Auth filter chain — multiple filters set auth

- SecurityConfig adds `ApiTokenAuthFilter`, `JellyfinAuthFilter`, and `SubsonicAuthFilter` (in that order) before `UsernamePasswordAuthenticationFilter`. The first filter to see a valid token wins — subsequent filters short-circuit via `if (SecurityContextHolder.getContext().authentication == null)`. If you enrich one auth class with a new field (e.g., `deviceId`), the controller may still receive the OTHER auth type if its filter ran first. Either enrich all auth classes or extract a common interface (e.g., `DeviceBoundAuthentication`) implemented by each, then `as? DeviceBoundAuthentication` in the controller.

## Proxy chain strips non-Bearer auth methods

- Between Cloudflare/Traefik and the v2 backend, ONLY `Authorization: Bearer <token>` reaches the auth filter intact. `X-Emby-Token`, `X-Emby-Authorization: MediaBrowser Token="..."`, and `?api_key=<token>` query all return 401 through the proxy chain, while ALL FOUR succeed when hitting the pod directly via `kubectl port-forward`. Root cause not identified — Traefik middleware (`strip-api` only strips `/api` prefix) and Cloudflare don't show explicit header/query filtering. Workaround shipped: PWA sends `Authorization: Bearer` for all `fetch()` calls and writes the token to a `yay_token` cookie for HTML5 `<audio src>` and `EventSource` (neither can send custom headers). Backend `JellyfinAuthFilter` + `ApiTokenAuthFilter` accept the cookie as a 5th auth source.
- Lesson: when the same code authenticates correctly via port-forward but fails through the public URL, the bug is in the proxy chain, not the code. Test step: pull a fresh login token → hit each auth surface twice (port-forward vs public URL) → diff statuses to localize.
