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
- Lighthouse LCP budget of 2500ms is too tight for GitHub Actions runners — 3000ms is more realistic for CI

## SonarCloud

- `plsql:VarcharUsageCheck` on PostgreSQL migration files is a false positive (Oracle-specific rule)
- `yay-tsa-v2/` subtree docker-compose `PASSWORD` env vars are flagged as vulnerabilities — false positives for local dev credentials
