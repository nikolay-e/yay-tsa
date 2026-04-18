# QA Methodology Learnings

## CI Investigation

- `gh run view --log-failed` only works after the ENTIRE run completes, not per-job — use `gh api` to get individual job logs instead
- Search job logs with `grep -E "passed|failed|exit code"` for quick test summary
- E2E test failures are listed with numbered format: `N) [project] › file:line › Test Name`

## CD Verification

- ArgoCD Image Updater typically takes 60-90s after CI completes
- Audio separator and feature extractor images may lag behind if their code wasn't changed — compare only frontend/backend images
- Backend logs during E2E runs show integration test traffic (SQL injection payloads, 401s) — this is normal test behavior, not real attacks

## Browser QA

- 401 on `/api/auth/me` is expected on login pages (unauthenticated check)
- Check browser console AFTER page fully loads and navigation settles — immediate checks catch transient errors
- Version badge on login page shows deployment version — verify it matches target commit

## Mobile E2E Tests

- Mobile viewport tests use a separate Playwright project (`[mobile]`) with Pixel 7 dimensions (412px)
- PlayerBar renders a mini-bar on mobile (`md:hidden`) and hides it with CSS `invisible` when full player opens
- Test IDs must be present in BOTH PlayerBar mini-bar AND MobileFullPlayer for `:visible` selectors to work across states
- Modal components must use `createPortal` to escape invisible parent containers — otherwise dialogs are invisible when opened from MobileFullPlayer
- `ensureControlsVisible()` opens the full player on mobile, which changes which elements are visible — test locators using `:visible` must account for this state change

## SonarCloud

- SonarCloud API: Bearer auth, organization param required for project search, pagination via `p=&ps=100`
- Quality gate can fail on `new_reliability_rating` even with a single BUG in new code
- `plsql:VarcharUsageCheck` on PostgreSQL migration files is a false positive (Oracle-specific rule)
- `void` operator usage in TypeScript: replace with `.catch(() => {})` for floating promises
- Nested ternaries in JSX: flatten to `&&` conditional rendering pattern

## General

- Run type-check, lint, and format before committing to catch issues early
- Pre-commit hooks auto-format with Prettier — run `npm run format` first to avoid surprises
- When fixing SonarCloud issues, removing dead code (like unused ternary branches) may leave unused variables — check for cascading removals
