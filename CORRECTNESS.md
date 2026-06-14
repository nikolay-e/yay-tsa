# CORRECTNESS — /review-correctness findings log

## 2026-06-14 17:00 · 65335be0 · session diff 430b35b0..HEAD (metadata-enricher, audiobook exclusion, /recommend/discover, frontend home-search/ExploreNew/panel-autoclose, #226 conformance fixes)

### TL;DR

The shipped logic is mostly correct — the core idempotency, the audiobook-exclusion SQL semantics, the discovery "heard" subtraction, and all of the #226 conformance fixes (BuildSha plumbing, CI 7-hex poll, nginx dotfile problem+json, pipefail guards) verified correct. Five real defects found; the four that bite or are cheap-and-important were FIXED this pass.

### Top Issues (all FIXED ✅)

1. ✅ [JellyfinItemsController.kt:119 search branch] — `/Items` search path ignored the parsed `excludedGenres`, so SearchPage album/artist/track sections surfaced audiobooks despite the client sending `ExcludeGenres`. **Fires on every text search.** Fixed: threaded `excludedGenres` through `searchText` → `LibraryQueries`/`LibraryQueryPort`/`JpaLibraryQueryPort`/`InMemoryPorts`, added 3 native `search*ExcludingGenres` queries mirroring the browse-exclusion predicate.
2. ✅ [images table / V006] — no partial-unique index on `images(entity_id) WHERE is_primary`; the non-transactional enricher `find→delete→save` racing the transactional scanner could create two primary rows → `findByEntityIdAndIsPrimaryTrue` throws `NonUniqueResult` and breaks ALL cover serving for that album (sticky). Fixed: `V006__images_primary_unique.sql` dedups then creates the partial unique index (race now a caught constraint violation).
3. ✅ [MetadataEnricher.kt enrichArtists/enrichAlbums] — broad `catch(Exception)` treated a deterministically-throwing row the same as a transient one; the row never gets `metadata_checked_at` and head-of-line-blocks the `OFFSET 0 LIMIT 50` batch once ≥50 accumulate. Fixed: only `MetadataProviderUnavailableException` defers (retry); any other exception marks the row checked to unblock the batch.
4. ✅ [ReleaseMatcher.kt:match] — two titles normalizing to "" gave distance 0.0 = perfect match → false metadata binding for symbol-only titles. Fixed: `if (normalize(local.title).isEmpty()) return null`.

### Downgraded / not a prod bug

- [JellyfinAdaptiveController.isAudiobookTrack] inspects only `track.genre` (arbitrary primary genre), so a multi-genre audiobook could leak into discover/daily-mix/radio. **Refuted as live bug** by synthesis: the scanner's `LibraryWriter.linkGenre` writes exactly ONE genre row per track, so "primary genre" == "only genre" today. Future-proofing only (broaden to full genre set if multi-genre tracks ever appear). DEFER.

### Systemic Patterns

- **Denormalized-vs-join genre divergence:** `/Items` browse uses the full `entity_genres` join (correct); the adaptive surfaces use the single denormalized `track.genre`. Harmless today (1 genre/track) but a latent asymmetry.
- **Worker writes without DB constraints:** the enricher writes the shared library schema non-transactionally; the missing primary-image uniqueness was the one place this could corrupt reads (now fixed with V006). Treat every worker-written invariant as needing a DB constraint, not just application-level guards.

### False Positives (verified safe)

- All #226 fixes correct (BuildSha env→property→@Value→DTO chain; both CI polls use matching `main-<sha7>` 7-hex; `|| true` guards pipefail; nginx `^~ /.well-known/` precedence intact; v2-ci pre-rollout old image correctly fails to match).
- Audiobook browse-exclusion SQL: keep-if-≥1-non-excluded-track semantics, case-folding, `:excludedGenres` bound param, empty-exclusion fallback, count/browse population — all correct.
- Discovery heard-set subtraction, dedup, audiobook+red-line filtering, pool multipliers — correct (random fallback can under-fill for power users, acceptable degradation).
- PlayerBar pathname effect (closes 5 panels, correctly excludes full-player with its own history machinery); ExploreNew query keys / shape; entity `val→var` (named args, plain class); enricher-written cover servability & `CREATE_NEW` no-clobber; the prod `metadata_checked_at=NULL` re-queue reset.

### Notable lower-severity (logged, not fixed)

- 🟡 HomeSearch navigates per-keystroke (no `replace:true`) → one history entry per distinct `?q` + focus drop; SearchPage seeds `?q` once and doesn't resync. UX, frontend-only.
- 🟢 Enricher logs "0 artists, 0 albums" on a full provider outage (indistinguishable from idle) — no metric/counter.
- 🟡 Mismatched-extension cover (user `cover.png` + enricher `cover.jpg`) can coexist with non-deterministic scanner pickup.

Scouts/synthesis: 5/2.
