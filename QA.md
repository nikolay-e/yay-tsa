# QA Methodology Learnings

## CI Investigation

- `gh run view --log-failed` only works after the ENTIRE run completes — use `gh api repos/owner/repo/actions/jobs/JOB_ID/logs` for individual job logs mid-run
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
- `getByRole('button', { name: 'Shuffle' })` can match the AlbumDetailPage text-button "Shuffle" (no `aria-pressed`) — use `locator('button[aria-label="Shuffle"][aria-pressed]:visible')` to target only player controls
- Badge text on mobile may include unit suffix (e.g., "15m") — use `Number.parseInt(text ?? '0', 10)`, not `Number(text)` which returns NaN
- `setPointerCapture` throws `DOMException` for synthetic pointer events dispatched by tests — guard with try/catch in `handlePointerDown`
- Monkey test 300s timeout on mobile CI is expected when iterations don't complete — fixing uncaught exceptions reduces failed recoveries and speeds up test

## SonarCloud

- `plsql:VarcharUsageCheck` on PostgreSQL migration files is a false positive (Oracle-specific rule)

## General

- Run type-check, lint, and format before committing to catch issues early
- Pre-commit hooks auto-format with Prettier — run `npm run format` first to avoid surprises
