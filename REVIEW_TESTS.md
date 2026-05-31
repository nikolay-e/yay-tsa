# Test Coverage Gap Analysis — yay-tsa (Pass 2, 2026-05-24)

> `/review-tests` re-run after Pass-1 remediation. Pass-1 added: `MlSimilarityTest`, `AffinityWorkerIntegrationTest`, `AuthLogoutIntegrationTest`, `ItemsPaginationIntegrationTest`, `AudioStreamIntegrationTest`; fixed codec mapping, `/Items` TotalRecordCount, karaoke retry (V002). This pass verifies those closures and maps the remaining frontier. Testing philosophy: integration/E2E only — "missing test" means missing integration/E2E coverage of a behavior. (Prior pass archived at /tmp/REVIEW_TESTS.prev.md.)

## Round 1 — Pass-1 closure verification (are the 5 new tests real?)

All 5 are **PARTIAL**, not full closures:

- **MlSimilarityTest** — exercises only the MERT path; the CLAP/Discogs `.ifEmpty{}` fallback (`JpaMlQueryPort.kt:66`) is never reached (a mutation replacing it with `emptyList()` survives). The "empty when no embeddings" test passes for the _wrong reason_ (falls through CLAP on a seed with no CLAP). 🟡 uncovered: MERT-null→CLAP and MERT/CLAP-null→Discogs fallthrough.
- **AffinityWorkerIntegrationTest** — proves sign of PLAY_COMPLETE↑/SKIP_EARLY↓ but calls `aggregate()` once: the **additive UPSERT** (`uta.affinity_score + EXCLUDED…`) and **watermark** logic are unguarded (a clobber mutation survives). 🟡.
- **AuthLogoutIntegrationTest** — Bearer revocation closed; expired-token, deactivated-user, and `?api_key=<revoked>` paths still open. 🔵.
- **ItemsPaginationIntegrationTest** — `/Artists` (non-search) only; `/Artists?SearchTerm=`, `/Artists/AlbumArtists`, `/Items?IncludeItemTypes=MusicArtist` totals uncovered. 🟡.
- **AudioStreamIntegrationTest** — ALAC only; wma/ape/dsf/octet-stream fallback, the **Subsonic `/rest/stream` ResourceRegion path**, the 200 full-file branch, and the relative-`source_path` branch all uncovered. 🟡. **Latent bug found: invalid Range `bytes=abc-` falls back to `0L` instead of returning 416** — no test catches it.

## Round 1 — Library scanner hygiene (still no `src/test`)

Confirmed `infra-library-scanner` has zero tests. **Feasibility resolved:** a Testcontainers test of `LibraryWriter.upsertTrack` IS viable — `AudioFileIO` only reads STREAMINFO + tags (not samples), so a checked-in silent FLAC fixture (`ffmpeg -f lavfi -i anullsrc -t 3 -c:a flac`) + `audioFile.tag.setField` for tag mutations works. Extend `LibraryPersistenceTestBase`.

- 🔴 `usableTag()` `^[#?\s]+$` (`:384`) — placeholder rejection unguarded; **regex gap: dash-only junk (`"- -"`, `"---"`), `"N/A"` pass through** and become names.
- 🔴 short-duration skip `<2000ms` (`:131-133`) — boundary (1999/2000) untested.
- 🟡 `stripTrackNumberPrefix`/`stripLeadingYear` (`:375,379`) — `01 - Eyeless`→`Eyeless`, `2019 - Abbey Road`→`Abbey Road` untested.
- 🟡 `repairExistingTrackLinkage` (`:290-337`) — v1-abspath→relative re-path idempotency + title-repair untested.
- 🔵 no `ensureArtistImage` — artist folder images never collected (feature gap).

## Round 1 — Playlists CRUD + Favorites (zero HTTP coverage, harness-ready)

- 🔴 `POST /Playlists`→`GET /Items/{uuid}` resolves as Type=Playlist not 404 (`JellyfinItemsController.kt:295` `runCatching` swallows non-UUID → 404).
- 🔴 best-effort delete-rollback on initial add failure (`JellyfinPlaylistsController.kt:95-104`) — assert playlist actually gone.
- 🟡 add-items→childCount; Move/reorder `coerceIn(0,size)` at 0/size/>size (`:224`); remove item; delete→404; favorite toggle + `IsFavorite` round-trip (`JellyfinFavoritesController.kt:30-44`).
- 🔵 favorites persistence across re-login (OCC on preferences aggregate).
- All testable via `HttpIntegrationTestBase` (seed user via authUseCases, library via JdbcTemplate).

## Round 1 — OCC / idempotency / outbox at the wired layer (still in-memory only)

- 🔴 `SpringTransactionalCommandExecutor` referenced by **no test** — outbox-rollback atomicity (handler ok → outbox written → `save` throws → row must vanish) only proven via `DirectTransactionalExecutor`.
- 🔴 `OutboxPoller` zero tests — crash between `publish()` and `save(entry)` → re-delivery (FOR UPDATE SKIP LOCKED guards concurrent, not crash).
- 🔴 `RewriteQueueTail` idempotency fingerprint bug — `newTail` entries + `idempotencyKey` use fresh `UUID.randomUUID()` each call (`LlmOrchestrator.kt:132,145`) → same key/different payloadHash → `InvariantViolation` on every retry. Logged as error each LLM retry.
- 🟡 `RewriteQueueTail` OCC at use-case layer + real `JpaIdempotencyStore` unique-constraint — only in-memory.
- The `AffinityWorkerIntegrationTest` harness proves real-executor app-level tests are viable for all of these.

## Round 1 — Multi-protocol consistency + Subsonic/MPD/MCP

- 🔴 Zero cross-protocol state tests — write via Jellyfin `POST /UserFavoriteItems/{id}` → read via Subsonic `GET /rest/getStarred2` (same preferences store) never asserted. Harness-ready.
- 🔴 MCP `/mcp` endpoint zero HTTP tests; `McpTools.kt:196` unchecked-casts `args["user_id"] as String` → `ClassCastException`→500 not a JSON-RPC error.
- 🟡 `CapabilitiesCompletenessTest`/`testCapabilitiesRegistry()` Jellyfin-only — MPD(5)/MCP(4)/Subsonic capabilities unverified against the sealed command set.
- 🟡 MPD `setvol/repeat/random/clear/add` → bare `ok()` (`MpdCommandHandler.kt:58-63`) instead of `ACK [5@0]` (the `MpdFailureTranslator` wiring exists, unused) — silent-success lie; `parseLine` quoted/unclosed-quote + `idle` (returns hardcoded `changed: player` — a lie, real idle blocks) untested.
- 🟡 Subsonic wrong-password / `enc:` hex / token-mode (`t`/`s` → `return false`) → 401, none tested.

## Round 1 — Frontend playback races (verified against e2e specs)

- 🔴 `previous()` <3s reads RAF-batched store not engine (`player.store.ts:870`); `playback.spec.ts:42-61` forces the >3s branch — <3s race untested. (Medium Playwright difficulty via `page.clock`.)
- 🔴 `pause()` mid-`seamlessSwitch()` crossfade (`html5-audio.ts:901-1005`) — `performElementSwap` runs unconditionally after the 150ms fade; can silently unpause. (Hard.)
- 🔴 background-tab AudioContext-suspend recovery (`html5-audio.ts:208-237`) — zero coverage. (Medium.)
- 🔴 karaoke mid-track switch position drift ±1s (`player.store.ts:343-359`) — **no karaoke e2e exists at all**. (Hard.)
- 🟡 `play()` during pending `load()` (independent promise chains, `html5-audio.ts:321-415`); `repeat-one` actual restart (`:543-547`, only ARIA tested — easy); shuffle on→off DOM order restoration (only ARIA tested — easy).

## Round 1 — Lease / SSE / multi-device / search / Group Sync

- 🔴 **Group Sync is frontend-only** — `packages/core/.../group-sync.service.ts` calls `/v1/groups/*` which **do not exist** in any `.kt` → every request 404s; UI shows `GroupSyncPanel`. Silently broken end-to-end. (Needs product decision: implement backend or remove UI.)
- 🔴 SSE `/v1/me/devices/events` is a **stub** (`JellyfinDevicesController:61` static `ready`); no test for `text/event-stream`, 401-without-token, or second-device-appears. `WebSocketAuthInterceptor` bad-token untested.
- 🔴 lease contention via real Spring executor + Postgres OCC — only in-memory mock-flag (`LeaseLifecycleScenarioTest`).
- 🟡 **Search latent bugs**: `searchText` uses `ILIKE '%x%'` not pg_trgm (the `search_text` column exists but is unused in the query); **the 50-result cap is not enforced** (`limit` passed straight from HTTP); `Recursive` param accepted but ignored. Empty/1-char query untested.
- 🟡 `nowPlayingItemName` absent from `DeviceSessionDto`/outbox broadcast — UI track-name sync untested.

## Round 2 — Bug confirmation (shipped bug vs. mere test gap)

- **CONFIRMED-BUG — Subsonic `/rest/stream` has NO range validation** (`MediaStreamController.kt:68-81`): `bytes=abc-`→start 0; `bytes=<past-EOF>-`→negative `contentLength` → `ResourceRegion` with bad offsets → garbage/empty 206 with a lying `Content-Range`. Fix: add the `start>=fileSize||end<start → 416` guard the Jellyfin path already has. (Jellyfin path: **NOT-A-BUG** — guard at `:107-111` catches it; benign.)
- **CONFIRMED-BUG — search `Limit` uncapped** (`JellyfinItemsController.kt:41` → `searchText(term, limit, …)` no `coerceAtMost`): `?Limit=1000000` → unbounded result set / memory. Fix: `limit.coerceIn(1,200)`. (ILIKE-vs-pg_trgm = perf/quality → NOT-A-BUG; `Recursive` ignored = cosmetic.)
- **CONFIRMED-BUG — MCP `args["user_id"] as String`** (`McpTools.kt:188,200`): missing field → CCE/NPE → HTTP 500, not a JSON-RPC error. Fix: `as? String ?: errorResult(...)`.
- **CONFIRMED-BUG — MPD silent `ok()`** (`MpdCommandHandler.kt:58-63`) for clear/add/setvol/repeat/random + `idle` hardcoded `changed: player`. Client believes mutations succeeded. Fix: `ack(5,cmd,"unsupported")` (translator already wired, unused).
- **PRODUCT-DECISION — Group Sync** (`/v1/groups/*` absent; `PlayerBar.tsx:530,556` renders `GroupSyncPanel` unconditionally, no flag): confirmed silent-404 end-to-end. Remedy needs a call: implement backend OR hide UI.
- **NOT-A-BUG-TODAY — LlmOrchestrator fresh-UUID idempotency** (`LlmOrchestrator.kt:132,145`): real design gap, but `LlmClient.complete()` returns null + `enabled=false` → orchestrator early-returns at `:104`, never reaches the command. Latent; fix when LLM is wired (deterministic key from `(sessionId, baseQueueVersion, suggestionHash)`).

## Round 2 — Severity recalibration + ROI

**Down-rate (solo, autoqa-status-only):** lease contention, OutboxPoller crash-redelivery (idempotent consumer), SpringExecutor atomicity, pause-mid-crossfade, background-tab recovery → 🟡 (rare/self-healing for one user). **Up-rate / stays 🔴:** confirmed shipped bugs above; the PARTIAL Pass-1 closures (false confidence — treat each surviving mutation as 🔴-to-guard); scanner `usableTag`/`<2000ms` (silent permanent library corruption autoqa can't see); Group Sync 404.

**ROI top-10 (impact×likelihood÷effort) — quick-win cluster ★ = extend an existing test:**

1. **Subsonic range 416 guard** — fix + 1 assertion. Confirmed bug, near-zero effort.
2. ★ **MlSimilarityTest CLAP/Discogs fallthrough** — seed MERT-null+CLAP, then MERT/CLAP-null+Discogs; kills surviving `emptyList()` mutant on `JpaMlQueryPort.kt:66`.
3. ★ **AffinityWorker second-tick** — call `aggregate()` twice; assert additive sum + watermark advance; kills clobber mutant.
4. **Search `Limit` cap** — `coerceIn(1,200)` + over-limit assertion. Confirmed DoS bug.
5. **Scanner-hygiene fixture bundle** — one silent-FLAC fixture guards `usableTag` (+dash/`N/A` regex fix), `<2000ms` boundary, `stripTrackNumberPrefix`, `stripLeadingYear` in one `infra-library-scanner` Testcontainers base (feasibility proven).
6. **Playlist `POST→GET /Items/{uuid}` resolve** (Type=Playlist vs non-UUID 404) — `JellyfinItemsController.kt:295`.
7. ★ **AudioStream codec fallback** — add wma/ape/dsf/octet-stream + 200 full-file rows.
8. **MCP cast → JSON-RPC error** — fix + malformed-tool-call test.
9. **MPD unsupported → ACK** — fix `ok()`→`ack(5,…)` + capability test.
10. ★ **ItemsPagination search branch** — `/Artists?SearchTerm=` total + over-limit.

**Cheapest cluster:** 2,3,7,10 extend 4 existing tests (a handful of assertions) guarding ~10 behaviors + killing 2 mutants for near-zero effort.

## Round 2 — Systemic patterns

1. **Partial-closure / single-branch pinning (dominant).** Every Pass-1 test guards one branch's happy path; siblings/fallbacks/boundaries open (MERT-only, single-tick, ALAC-only, Artists-only, Bearer-only). Root cause: tests written to make the fix pass, not to pin the contract. Fix: **mutation-testing gate (PIT/Stryker) on changed files** as the closure criterion, not line coverage.
2. **Latent-bug-behind-missing-test.** Gaps were untested _and wrong_ (Subsonic range, search cap, MPD ok(), MCP cast). Root cause: adapters hand-translate edges with no negative/boundary contract test. Fix: one negative HTTP test per adapter endpoint before green.
3. **Frontend-feature-without-backend** (Group Sync). Currently a one-off, but the _shape_ recurs because nothing cross-checks the sides. Fix: CI route-manifest diff (`packages/core` service paths vs Spring `@RequestMapping`).
4. **Worker/edge modules still have no `src/test`** (`infra-llm/-library-scanner/-media/-karaoke-worker/-notifications`) — the karaoke V002 fix shipped into a test-less module. Fix: mandatory test source set per non-trivial module.
5. **Stub/disabled paths excluded from tests** (SSE stub, prod-disabled Demucs, LLM stub) — inert code still has a violated contract. Fix: test the real contract behind a flag.

- **Meta-root-cause:** Pass-1 optimized for "make the fix pass," not "pin the contract." Single lever for 1/2/5 = mutation-testing gate on changed files.

## Round 3 — Prosecution (top 10)

Pass-1's 5 green tests are a _false sense of security_: each pins one branch's happy path while siblings ship untested, and behind four untested siblings sit confirmed reproducible bugs CI vouches for. 1) Subsonic `/rest/stream` serves corrupt/negative-length 206 with a lying Content-Range (`MediaStreamController.kt:68-80`). 2) Search `?Limit=1e6` unbounded-memory DoS (`JellyfinItemsController.kt:79`). 3) MCP `as String` → 500 not JSON-RPC error (`McpTools.kt:188`). 4) MPD lies "OK" on every mutation (`MpdCommandHandler.kt:58-66`). 5) Group Sync 404s end-to-end, UI rendered unconditionally (`PlayerBar.tsx:530`). 6) MlSimilarityTest passes for the wrong reason — CLAP/Discogs fallthrough never hit (`JpaMlQueryPort.kt:66`). 7) AffinityWorker single-tick — additive UPSERT/watermark unguarded. 8) AudioStream ALAC-only — sits adjacent to bug #1 and never touches it. 9) AuthLogout Bearer-only — `?api_key=<revoked>` may still stream. 10) ItemsPagination `/Artists` non-search only. Indictment: the tests pin the line that made the fix compile, not the contract; until the closure criterion is _mutation survival on changed files_, Pass-1 converted "untested" into "falsely trusted."

## Round 3 — Defense

- **DISMISS (moot for solo PWA→Jellyfin user):** lease contention (one human can't race themselves), cross-protocol simultaneous-client consistency (user talks only to Jellyfin), outbox crash-redelivery (idempotent consumer, self-heals), MPD/MCP convenience surfaces (not on daily path), Subsonic range (Jellyfin path _has_ the 416 guard at `:107` — only Symfonium/Finamp bitten), LlmOrchestrator (unreachable, `enabled=false`+stub).
- **MITIGATED:** Pass-1's PARTIALs closed the _prod-shipped_ bugs (codec, TotalRecordCount, karaoke per QA.md) and guard the path the user triggers; surviving mutants are sibling fallbacks — cheap to extend, not a crisis.
- **CONCEDE (consequential for THIS deployment, autoqa-blind):** scanner `usableTag()` regex (permanent corruption of the user's OWN library), search uncapped Limit (self-DoS from own client), Group Sync (dead button in the user's face — needs product call), and the daily-path PARTIALs (codec variety, playlist `POST→GET` resolve).

## Final Verdict

### TL;DR

Pass-1 fixed the three prod-shipped bugs but its 5 tests are all **partial** — they pin one branch and leave siblings, boundaries, and fallbacks open (mutations survive), creating false confidence. Stripping the solo-deployment noise, the genuinely consequential, autoqa-invisible frontier is small and concrete: **two confirmed shipped bugs (scanner placeholder-tag corruption, search uncapped Limit), one dead feature (Group Sync 404), and a handful of near-zero-effort test extensions that kill surviving mutants on the user's daily path.**

### Top Issues (fix immediately) — self-verified ✅

1. ✅ **Scanner `usableTag()` accepts junk** (`LibraryWriter.kt:384` `^[#?\s]+$`) — `"- -"`, `"---"`, `"N/A"` become permanent artist/track names in the user's library. Fix the regex (`^[-#?\s]+$` + `N/A`) AND add the `infra-library-scanner` Testcontainers fixture test (feasibility proven: silent-FLAC fixture + `tag.setField`). Confirmed bug + the highest-value missing test bundle.
2. ✅ **Search `Limit` uncapped** (`JellyfinItemsController.kt:41,79` → `searchText(term, limit, …)` no coerce) — `?Limit=1000000` = self-DoS / huge payload. Fix `limit.coerceIn(1,200)`; guard with an over-limit assertion.
3. ✅ **Subsonic `/rest/stream` no range validation** (`MediaStreamController.kt:68-80`) — `bytes=<past-EOF>-` → negative `contentLength` → corrupt 206 with lying Content-Range. Add the `start>=fileSize||end<start → 416` guard (Jellyfin path already has it). Confirmed bug (lower blast radius — Subsonic clients only).
4. ✅ **Group Sync 404** (`packages/core/.../group-sync.service.ts` → `/v1/groups/*` absent from all `.kt`; `PlayerBar.tsx:530,556` renders the panel unconditionally) — every action 404s. **Product decision:** implement backend or hide the UI.
5. **MCP `as String` → 500** (`McpTools.kt:188`) and **MPD silent `ok()`** (`MpdCommandHandler.kt:58-66`) — confirmed contract bugs; low blast radius (agent/ncmpcpp only) but cheap to fix + guard.

### Quick-win test extensions (kill surviving mutants, ~near-zero effort)

Extend 4 existing tests: **MlSimilarityTest** (MERT-null→CLAP, MERT/CLAP-null→Discogs fallthrough), **AffinityWorkerIntegrationTest** (second `aggregate()` → additive sum + watermark), **AudioStreamIntegrationTest** (wma/ape/dsf/octet-stream + 200 full-file + invalid-range→416), **ItemsPaginationIntegrationTest** (`/Artists?SearchTerm=` + over-limit). Plus **playlist `POST→GET /Items/{uuid}` resolve** (new, cheap).

### Systemic Patterns

1. **Partial-closure / single-branch pinning** (dominant) — tests written to make the fix pass, not pin the contract; surviving mutants prove it. Lever: **mutation-testing gate (PIT) on changed files** as the closure criterion.
2. **Latent-bug-behind-missing-test** — adapters hand-translate edges with no negative/boundary test; wrong behavior shipped invisibly. Lever: one negative HTTP test per adapter endpoint.
3. **Frontend-feature-without-backend** (Group Sync). Lever: CI route-manifest diff (`packages/core` paths vs Spring `@RequestMapping`).
4. **Worker/edge modules still have no `src/test`** (`infra-llm/-library-scanner/-media/-karaoke-worker/-notifications`) — karaoke V002 shipped into a test-less module. Lever: mandatory test source set per module.
5. **Stub/disabled paths excluded from tests** (SSE stub, prod-disabled Demucs, LLM stub) — inert code still has a violated contract.

### False Positives / Down-rated

- Jellyfin invalid-Range — NOT a bug (guard at `MediaStreamController`-equivalent `:107-111` catches it).
- Search ILIKE-vs-pg_trgm — perf/quality, not correctness. `Recursive` ignored — cosmetic.
- LlmOrchestrator fresh-UUID idempotency — real design gap but **unreachable today** (LLM stub returns null, `enabled=false`); fix when LLM is wired.
- Lease contention, cross-protocol concurrency, outbox crash-redelivery — moot/low-impact for a single-user deployment (→ 🟡).

### Verdict

The remediation was real but incomplete in a specific, fixable way: the tests pin happy paths, not contracts. The actionable next step is small — fix two confirmed library/search bugs, decide Group Sync, and extend four existing tests to kill surviving mutants — not another broad sweep.

## Audit Log

- Date: 2026-05-24 (Pass 2, post-remediation)
- Project: /Users/nikolay/yay-tsa
- Skill: /review-tests
- Findings: 5 confirmed bugs (2 high-consequence for solo: scanner-tag, search-cap), 1 dead feature (Group Sync), 5 PARTIAL Pass-1 closures, plus the unchanged worker-no-test frontier
- Agents used: 8 + 3 + 2 + 1 = 14

---

# Pass 3 (2026-05-31) — Coverage of the robustness remediation

> New `/review-tests` run after the P0/P1/P2 robustness fixes (IDOR authz, WebSocket per-user routing, idempotency ON CONFLICT, /Items pagination via `browseTracksByArtist`/`getTracksByIds`, token-validation Caffeine cache + `TokenRevokedEvent` eviction, karaoke RFC 7233 clamp, MPD `list_OK`, ghost-row `library_root` backfill, SW `registerSW`, infinite-query `maxPages`). Focus: do these changes have integration/E2E coverage, and what user-facing behavior can now break silently? Pyramid R1=10 (user requested) → R2=5 → R3=2 → R4.

## Round 1 — Authorization coverage

### What was checked

Production code: `JellyfinAdaptiveController` (lines 247–293), `JellyfinAuthController` (lines 106–124), `TokenValidationCache` (all), `AuthLogoutIntegrationTest`, `AuthIntegrationTest`, `HttpIntegrationTestBase`.

---

### Gap 1 — IDOR on `GET /v1/users/{userId}/preferences` and `PUT /v1/users/{userId}/preferences` 🔴

**Code**: `JellyfinAdaptiveController.kt:251` (`if (principal.name != userId) return 403`) and `:279`.

**What exists**: Zero integration tests for these endpoints exist anywhere in `app/src/test`. The check was added to production but never exercised. The cross-user case (user A reading user B's preferences) is completely untested. The happy path (self-read) is also untested.

**Scenarios missing**:

- User A (`token_a`) does `GET /v1/users/{id_of_B}/preferences` → expects **403**
- User A does `PUT /v1/users/{id_of_B}/preferences` → expects **403**
- User A does `GET /v1/users/{id_of_A}/preferences` (self) → expects **200**
- User A does `PUT /v1/users/{id_of_A}/preferences` → expects **204**

**Testable via** `HttpIntegrationTestBase`: yes — seed two users with distinct tokens (same pattern as `AuthLogoutIntegrationTest`), call `get("/v1/users/$idB/preferences", tokenA)`, assert 403.

---

### Gap 2 — IDOR on `GET /Users/{userId}` 🔴

**Code**: `JellyfinAuthController.kt:111` (`if (principal.name != userId && !isAdmin → 403`).

**What exists**: `JellyfinApiIntegrationTest` calls `GET /Users/$userId` at line 54 but only for the _same_ user (self-read). No cross-user test, no admin-reads-other-user test.

**Scenarios missing**:

- Non-admin user A reads user B → expects **403**
- Admin user reads user B → expects **200**
- Self-read by non-admin → expects **200** (covered indirectly, but not as an explicit auth assertion)

**Testable via** `HttpIntegrationTestBase`: yes — `seedUser()` twice (one admin, one regular), call `get("/Users/$idB", tokenA)`.

---

### Gap 3 — `TokenValidationCache` correctness under revocation 🟡

**Code**: `TokenValidationCache.kt:29–44`. The cache has a 10-second TTL. `onTokenRevoked` evicts the entry synchronously. `AuthLogoutIntegrationTest` covers the normal logout path (eviction via event).

**Untested scenario**: A token is **revoked out-of-band** (e.g., admin deletes via `DELETE /Admin/Users/{id}` or direct DB action) **without** publishing `TokenRevokedEvent`. The cache entry survives for up to 10 s and the revoked token continues to authenticate. There is no test that directly calls `TokenValidationCache.invalidate` is skipped and the cache retains a now-invalid user, nor a test that a deactivated user mid-cache-lifetime is rejected (the filter re-checks `isActive` on the cached aggregate at `:31` — `cache.getIfPresent(key)?.let { return it }` returns the stale aggregate before that check if it was cached as active).

**Concrete scenario**:

- User logs in → token cached
- Admin sets `isActive=false` in DB
- Request within 10-second TTL → should → **401** (user disabled)
- Current behavior: cached `UserAggregate` has `isActive=true` from cache time → **passes auth** (stale data)

**Severity**: 🟡 — window is 10 s, not exploitable for arbitrary duration, but represents a real authentication bypass window for disabled users.

**Testable via** `HttpIntegrationTestBase`: yes — seed user, authenticate, directly call `authUseCases.execute(DeactivateUser(…))`, immediately re-request within TTL, assert 401.

---

### Sources consulted

- [OWASP BOLA/IDOR testing guide](https://owasp.org/www-project-web-security-testing-guide/latest/4-Web_Application_Security_Testing/05-Authorization_Testing/04-Testing_for_Insecure_Direct_Object_References)
- [Equixly: API Authorization Matrix testing 2025](https://equixly.com/blog/2025/10/07/authorization-matrix/)
- [Spring Cache integration testing patterns](https://dzone.com/articles/spring-cache-and-integration-testing)
- [Caffeine cache eviction in Spring Boot](https://medium.com/@AlexanderObregon/partial-cache-invalidation-in-spring-boot-with-caffeine-a5096a746feb)

---

## Round 1 — Idempotency & OCC coverage

### What changed

`JpaIdempotencyStore.store()` now calls `jpa.insertIfAbsent()` — a `@Modifying @Query(nativeQuery=true)` with `INSERT … ON CONFLICT (user_id, command_type, idem_key) DO NOTHING`. The old behaviour threw a PK-violation that was caught as `DataIntegrityViolationException` → `Failure.InvariantViolation` for concurrent same-key replays. The new behaviour silently no-ops instead.

---

### Gap 1 — No Postgres-wired test for `insertIfAbsent` native query 🔴

`infra-persistence/shared/src/test/` contains only `StringListAttributeConverterTest.kt`. There is **zero** test that instantiates `JpaIdempotencyStore` (or `IdempotencyRecordJpaRepository`) against a real Testcontainers Postgres. Native queries with database-specific syntax (`ON CONFLICT`) are **not validated by Spring/Hibernate until runtime**. If the schema path, column name, or Postgres version changes, the bug is invisible until production.

**How to test:** Add `IdempotencyStorePersistenceTest` extending the `AbstractPersistenceTest` pattern used in `infra-persistence/{auth,playback,library}`. Call `store()` once, assert `find()` returns the record, call `store()` again with the same key, assert no exception and `find()` still returns the original record.

**File to create:** `infra-persistence/shared/src/test/kotlin/dev/yaytsa/persistence/shared/IdempotencyStorePersistenceTest.kt`

---

### Gap 2 — Concurrent same-key replay not tested against real Postgres 🔴

The core-testkit property/scenario tests (`QueuePropertyTest`, `PlaybackUseCasesTest`, `StartPlaybackAtomicityTest`) use `InMemoryIdempotencyStore`. That store uses a plain `mutableMapOf` with no concurrency semantics. The _changed guarantee_ — that two parallel `store()` calls with the same key both succeed (one inserts, one no-ops) instead of one throwing `InvariantViolation` — is **completely untested** against Postgres.

A race between thread A (just finished handling) and thread B (concurrent retry) calling `store()` simultaneously is the exact scenario the `ON CONFLICT DO NOTHING` was introduced to fix.

**How to test:** In the new `IdempotencyStorePersistenceTest`, launch two `CompletableFuture` coroutines that call `jpaIdempotencyStore.store(…same key…)` concurrently, assert both return without throwing, and assert `find()` returns the single record.

---

### Gap 3 — Same key + different payload still returns `InvariantViolation`? 🟡

The previous `InvariantViolation` for mismatched payload came from `PayloadFingerprint` comparison in `core-application` (checked via `find()` before `store()` is called). The `ON CONFLICT DO NOTHING` path bypasses any payload comparison at the DB layer. If the application-layer `find()` check is removed or bypassed, a different-payload replay silently succeeds.

No HTTP-layer integration test exercises `POST /Sessions/Playing` (or any state-mutating endpoint) with the same `Idempotency-Key` header but a different body and asserts 422/conflict.

**How to test:** In `JellyfinApiIntegrationTest` (extends `HttpIntegrationTestBase` with real Postgres), POST a command with `Idempotency-Key: X`, then POST again with the same key but a modified body field, assert the response is `InvariantViolation` (4xx).

---

### Gap 4 — Sequential replay returns prior result? 🟡

No HTTP integration test sends the same `Idempotency-Key` header twice sequentially (same payload) and asserts the second response equals the first (idempotent success, not a re-execution). The invariant lives in `core-application` `find()`-before-execute logic, but the wired path through `AdapterCommandContextFactory` → `JpaIdempotencyStore` has no end-to-end test.

**How to test:** Same `JellyfinApiIntegrationTest` pattern: POST with explicit `Idempotency-Key` header, record response body + status, POST again identically, assert responses are identical.

---

### Gap 5 — `SpringTransactionalCommandExecutor` outbox-rollback atomicity 🟡

`SpringTransactionalCommandExecutor` catches `DataIntegrityViolationException` → rollback + `Failure.InvariantViolation`. The `insertIfAbsent` path no longer throws `DataIntegrityViolationException` on conflict. If any other constraint violation surfaces from a concurrent write inside the same transaction (e.g. outbox uniqueness), the catch clause still fires and rolls back the entire transaction including aggregate state. No test verifies that the outbox entry and aggregate save are atomic — i.e. that a mid-transaction failure leaves both rolled back.

**How to test:** `StartPlaybackAtomicityTest` already tests this pattern with `InMemoryIdempotencyStore`; a parallel persistence-layer test using Testcontainers that forces a constraint violation after `save()` but before `enqueue()` would close the gap.

---

### Sources consulted

- [DEV: Race condition in double-entry ledger and ON CONFLICT DO NOTHING fix](https://dev.to/xidoke/the-race-condition-a-stress-test-found-in-my-double-entry-ledger-and-how-i-fixed-it-b5o)
- [DZone: Why Your Idempotency Implementation Is Silently Losing Data](https://dzone.com/articles/phantom-write-idempotency-data-loss)
- [Spring Data JPA: HQL parser rejects INSERT … ON CONFLICT (nativeQuery required)](https://github.com/spring-projects/spring-data-jpa/issues/3689)
- [Baeldung: ON CONFLICT Clause for Hibernate Insert Queries](https://www.baeldung.com/hibernate-insert-query-on-conflict-clause)

---

## Round 1 — Library pagination coverage

**Sources:** `JellyfinItemsController.kt:129-135`, `JpaLibraryQueryPort.kt:144-163`, `AudioTrackRepository.kt:16-31`, `ItemsPaginationIntegrationTest.kt` (165 lines, no artist-pagination tests).

---

### Gap 1 — Artist recursive page slice + TotalRecordCount not tested against Postgres (CRITICAL)

`GET /Items?ParentId=<artistId>&Recursive=true&Limit=2&StartIndex=2` hits `browseTracksByArtist` → `findByAlbumArtistIdPaged` native SQL → `countByAlbumArtistId`. Zero tests exercise this path at all. The native `@Query` with `LIMIT`/`OFFSET` has never fired against a real Testcontainers Postgres in CI.
**What to add:** Seed 1 artist + 2 albums × 3 tracks each (6 total). Assert page 2 returns exactly 2 tracks, `TotalRecordCount=6`, and the items are disc/track-ordered (album_id then track_number), not name-ordered.
**File to extend:** `ItemsPaginationIntegrationTest.kt`.

---

### Gap 2 — Offset past end returns empty Items with correct total (HIGH)

No test verifies `StartIndex >= totalCount` returns `{"Items":[],"TotalRecordCount":6}` and HTTP 200. A broken implementation might 500 or return a negative offset to SQL.
**What to add:** Same 6-track fixture, request `StartIndex=100`. Assert `Items` empty, `TotalRecordCount=6`.

---

### Gap 3 — Partial last page count (MEDIUM)

`Limit=4&StartIndex=4` against 6 tracks should return 2 items with `TotalRecordCount=6`. Tests covering exactly this boundary don't exist for the artist branch.

---

### Gap 4 — `getTracksByIds` with a missing/vanished ID (HIGH)

`JpaLibraryQueryPort.getTracksByIds` (line 156-163): `findAllByEntityIdIn` silently drops missing IDs; the caller-order reconstruction (`uuids.mapNotNull { byEntityId[it] }`) also drops them. The favorites branch at line 107-109 calls this with IDs from the preferences aggregate, which may reference deleted tracks. No test confirms the surviving tracks come back in correct order and the response total is still `favorites.size` (line 110), not the shorter `pageTracks.size`.
**What to add:** Seed 3 favorites, hard-delete one track row mid-test, call `/Items?IsFavorite=true`. Assert `TotalRecordCount=3`, `Items.size=2` (or whatever the spec intends), and order of surviving items matches favorite position.

---

### Gap 5 — `assembleTracksInOrder` breaks caller order for artist-paged result (MEDIUM)

`browseTracksByArtist` calls `assembleTracksInOrder` (line 165-175): it rebuilds from `entityRepo.findAllById` which returns in arbitrary DB order, then does `trackJpas.mapNotNull { entities[it.entityId] }` — preserving the SQL ORDER BY. But the entity map lookup is fine only if entity IDs are stable. No test verifies the disc/track_number ordering is preserved end-to-end through the HTTP response JSON.

---

### Web references

- <https://use-the-index-luke.com/no-offset> — LIMIT/OFFSET correctness and boundary conditions
- <https://blog.jooq.org/2013/10/26/faster-sql-paging-with-jooq-using-the-seek-method/> — pagination boundary testing patterns

---

## Round 1 — Streaming/range coverage

### What exists

`AudioStreamIntegrationTest` has three tests:

1. ALAC `bytes=0-1023` → 206 + `audio/mp4`
2. WMA `bytes=0-1023` → 206 + `audio/x-ms-wma`
3. Subsonic `/rest/stream` `bytes=99999999-` (past-EOF open-ended) → 416

### Gap 1 — Karaoke stems: zero HTTP tests (CRITICAL)

`JellyfinKaraokeController.streamStem` (`adapter-jellyfin/src/main/kotlin/dev/yaytsa/adapterjellyfin/JellyfinKaraokeController.kt:99`) handles the clamping logic added in this changeset. There is not a single integration test covering any karaoke streaming path.

Untested scenarios (all via `GET /Karaoke/{trackId}/instrumental` or `/vocals`):

| Scenario                               | Expected                     | RFC ref                                                         |
| -------------------------------------- | ---------------------------- | --------------------------------------------------------------- |
| `bytes=0-<fileSize+1>` (end past EOF)  | 206, clamped to `fileSize-1` | RFC 7233 §2.1 — last-byte-pos ≥ length → interpret as remainder |
| `bytes=500-` (open-ended)              | 206 from byte 500 to EOF     | same                                                            |
| `bytes=-100` (suffix)                  | 206 last 100 bytes           | RFC 7233 §2.1 suffix-byte-range                                 |
| `bytes=<fileSize+1>-` (start past EOF) | 416 with `bytes */<size>`    | RFC 7233 §2.1 unsatisfiable first-byte-pos                      |
| No Range header                        | 200 full content             | —                                                               |
| `trackId` not found                    | 404                          | —                                                               |

Severity: **HIGH**. The clamping fix is the primary just-changed behaviour; it has no regression guard at all.

Testability: straightforward — create a temp file, seed a `KaraokeAsset` row directly via JDBC (same pattern as `AudioStreamIntegrationTest`), call `/Karaoke/{id}/instrumental` with various Range headers.

### Gap 2 — Subsonic `/rest/stream` open-ended 206 is untested (MEDIUM)

The existing 416 test (`bytes=99999999-` on a 2048-byte file, `start >= fileSize`) confirms 416. But `MediaStreamController` (`infra-media/src/main/kotlin/dev/yaytsa/inframedia/MediaStreamController.kt:92`) also handles clamping: `end = minOf(requestedEnd, fileSize-1)`. There is no test for `bytes=0-99999999` (end beyond EOF) → must return 206 clamped, not 416. This is the symmetric case of Gap 1 for the Subsonic path.

### Gap 3 — `/rest/download` entirely untested (MEDIUM)

`subsonicDownload` (`MediaStreamController.kt:29`) returns `Content-Disposition: attachment` with full body. No test verifies the header, content-type, or that a range header is correctly ignored (download bypasses range logic at line 81).

### Gap 4 — `/rest/getCoverArt` round-trip untested (MEDIUM)

`subsonicCoverArt` (`MediaStreamController.kt:34`) looks up `libraryQueries.getPrimaryImage(EntityId(id))` then serves the file. Per [OpenSubsonic spec](https://opensubsonic.netlify.app/docs/endpoints/getcoverart/), `id` refers to a `coverArt` ID only (not a raw path). `getAlbum` returns `coverArt = album.id.value` when `coverImagePath != null` (`SubsonicController.kt:159`). No test exercises the full round-trip: seed album with image → `getAlbum` → extract `coverArt` field → `getCoverArt?id=` → verify image bytes + content-type header. A wrong path in `getPrimaryImage` would silently 404 in production.

### Sources

- [RFC 7233 — HTTP Range Requests (IETF)](https://datatracker.ietf.org/doc/html/rfc7233)
- [OpenSubsonic getCoverArt spec](https://opensubsonic.netlify.app/docs/endpoints/getcoverart/)

---

## Round 1 — WebSocket/notifications coverage

### Production code surveyed

- `WebSocketNotificationPublisher` (infra-notifications): parses `userId` from payload JSON → `convertAndSendToUser(userId, "/queue/$context", payload)` else `/topic/$context`. `extractUserId` wrapped in `runCatching`.
- `WebSocketAuthInterceptor`: on STOMP CONNECT, reads `X-Emby-Token` or `Authorization: Bearer`, validates via `authQueries.findByApiToken`, sets `WsPrincipal(user.id)`; throws `MessageDeliveryException` if null.
- `WebSocketConfig`: `/user` prefix, simple broker `/topic`+`/queue`, endpoint `/ws` (no SockJS).
- `OutboxPoller`: 1-second `@Scheduled`, batch 50, calls `OutboxEntryProcessor.publishAndMark`.
- `OutboxJpaRepository.findUnpublishedByIdForUpdate`: native query with `FOR UPDATE SKIP LOCKED`.

**Zero STOMP tests exist.** `infra-notifications/src/` has only `main/`. No `WebSocketStompClient` usage anywhere in test tree.

---

### Gap 1 — User-scoped delivery isolation (CRITICAL)

`convertAndSendToUser` routes to `/user/{id}/queue/{context}` using the subscriber's principal name. If `userId` in payload does not match the connected user's principal, Spring's in-memory broker silently delivers to nobody — or to the wrong session if two users share a name collision. **No test verifies** that UserA's `PlaybackStateChanged` (with `userId=A`) arrives only to UserA's subscription and is invisible to UserB.

Tool: `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `WebSocketStompClient` (standard library). Two clients connect with different tokens; UserA triggers a playback change; assert UserA receives on `/user/queue/playback` and UserB does not.

**Severity: HIGH** — incorrect isolation is a privacy/correctness defect that works silently.

---

### Gap 2 — Broadcast to `/topic` for non-user events (MEDIUM)

`LibraryChanged`, `AdaptiveQueueChanged`, `PlaylistChanged` have no `userId` → `extractUserId` returns null → broadcast. No test subscribes to `/topic/library` or `/topic/playlists` and verifies delivery after a real domain event writes to the outbox.

---

### Gap 3 — `extractUserId` fallback on malformed payload (LOW-MEDIUM)

`runCatching` swallows JSON parse errors and null nodes, falling back to broadcast. No test sends `"{bad json"` or `{}` and asserts the message appears on `/topic/` instead of crashing. Edge case but reachable if outbox payload corruption occurs.

---

### Gap 4 — CONNECT rejection on invalid/missing token (HIGH)

`WebSocketAuthInterceptor` throws `MessageDeliveryException` on bad/absent token. This should terminate the connection. No integration test verifies a `WebSocketStompClient` using a garbage token fails to connect (vs. silently succeeds and sees no messages). Also untested: expired and revoked tokens.

---

### Gap 5 — `convertAndSendToUser` requires matching principal name (HIGH)

`WsPrincipal.getName()` returns `userId.value`. `convertAndSendToUser(targetUserId, ...)` must match that name exactly. If `userId` in the payload is a UUID string and `WsPrincipal` returns a different form, routing silently drops messages. No test validates end-to-end: real token → principal set → message delivered to correct `/user/queue/` subscription.

---

### Gap 6 — Outbox `FOR UPDATE SKIP LOCKED` under concurrent pollers (MEDIUM)

`findUnpublishedByIdForUpdate` uses SKIP LOCKED to prevent double-publish. No Testcontainers test runs two `OutboxPoller` instances concurrently and verifies each outbox entry is published exactly once (idempotency + no duplicate deliveries).

---

### How to test (Spring STOMP)

```kotlin
// @SpringBootTest(webEnvironment = RANDOM_PORT) + Testcontainers Postgres
val client = WebSocketStompClient(StandardWebSocketClient())
client.messageConverter = StringMessageConverter()
val session = client.connectAsync(
    "ws://localhost:$port/ws",
    object : StompSessionHandlerAdapter() {},
    StompHeaders().apply { set("X-Emby-Token", validToken) }
).get(5, SECONDS)
val received = LinkedBlockingQueue<String>()
session.subscribe("/user/queue/playback") { _, payload -> received.add(payload) }
// trigger domain command that writes outbox entry with userId
// await poller (poll up to 3s) and assert received.poll(3, SECONDS) != null
```

References:

- <https://rieckpil.de/write-integration-tests-for-your-spring-websocket-endpoints/>
- <https://medium.com/@MelvinBlokhuijzen/spring-websocket-endpoints-integration-testing-180357b4f24c>
- <https://docs.spring.io/spring-framework/reference/web/websocket/stomp/authentication.html>
- <https://www.javathinking.com/blog/websocket-authentication-and-authorization-in-spring/>

---

## Round 1 — Scanner/ghost-row coverage

**References:**

- <https://testcontainers.com/guides/testing-spring-boot-rest-api-using-testcontainers/> (Testcontainers + Spring Boot Postgres integration test pattern)
- <https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.query-methods.at-query> (native @Modifying @Query testing via JdbcTemplate seeding)
- <https://flac.sourceforge.net/documentation.html> (FLAC fixture creation with jaudiotagger for scanner tests)

---

### GAP 1 — Core ghost-row heal is UNTESTED (Critical)

**Scenario:** A TRACK row exists with `library_root IS NULL`, `source_path = "Artist/Album/track.flac"`. The file is deleted. Scanner runs `deleteVanishedTracks(root=/media/music, presentSourcePaths={...without that track})`. Expected: `backfillNullLibraryRoot("/media/music", "/media/music%")` adopts the row → sweep sees it → row is deleted.

**Status:** Zero test coverage. `LibraryWriterHygieneTest` has 4 tests; none seed a NULL-`library_root` row via JdbcTemplate and then invoke `deleteVanishedTracks`. The `backfillNullLibraryRoot` native UPDATE is therefore never called against a real Postgres instance in the test suite.

**File:line:** `LibraryWriter.kt:424` (`entityRepo.backfillNullLibraryRoot(...)`), `LibraryEntityRepository.kt:137`.

**Feasibility:** Trivially feasible. `LibraryWriterHygieneTest` already has `JdbcTemplate jdbc` and a live Testcontainers Postgres. Seed with `jdbc.update("INSERT INTO core_v2_library.entities(id, entity_type, source_path, library_root, ...) VALUES(?, 'TRACK', ?, NULL, ...)")`, call `writer.deleteVanishedTracks(root, emptySet())`, assert `entityRepo.count() == 0`.

---

### GAP 2 — LIKE-prefix isolation (cross-root non-adoption) UNTESTED (High)

**Scenario:** Root A = `/media/music`, Root B = `/media/music-extra`. A NULL-root row with `source_path = "Artist/Album/track.flac"` is scanned by Root B. The LIKE predicate `source_path LIKE '/media/music%'` matches `/media/music-extra%` paths as well — but the `libraryRoot` param is `/media/music-extra`, so the UPDATE sets them under Root B. A NULL-root row whose path is NOT under any root is never cleaned up.

**Actual bug risk:** `sourcePathPrefix = "$rootKey%"` — if Root A = `/media/music` and a row has a path relative to it (no root prefix in `source_path`), the LIKE matches nothing because source*path is \_relative* (`Artist/Album/track.flac`), not absolute. The LIKE `"/media/music%"` against `"Artist/Album/track.flac"` will never match. This means the entire backfill is silently a no-op for relative paths. **Critical logic bug, not just a gap.**

**File:line:** `LibraryWriter.kt:424`, `LibraryEntityRepository.kt:131-138`.

**Feasibility:** Same Testcontainers setup; seed a NULL-root row with a relative path, verify that `backfillNullLibraryRoot` returns 0 (no rows adopted), confirm the bug is real.

---

### GAP 3 — Prior Pass-2 bugs still present and still untested

- **`usableTag` `^[-#?\s]+$`** accepts `"- -"` (space between dashes is not the `\s` that's in the bracket, since `"-  -"` = hyphen, spaces, hyphen — but `"- -"` has a space between hyphens, which IS matched). Test at line 97 verifies `"- -"` falls back correctly. **COVERED** — Pass-2 was wrong here.

- **`<2000ms` skip boundary** (`durationMs < MIN_PLAYABLE_DURATION_MS`): test at line 116 covers a 1s file skipped. The exact boundary case (exactly 2000ms) is untested — file with `durationMs == 2000` should be accepted but there's no test confirming `== 2000` is not excluded by `< 2000`. Low severity, feasible to add.

**File:line:** `LibraryWriter.kt:136`, `LibraryWriterHygieneTest.kt:116`.

---

### Summary

| #   | Scenario                                         | Severity | Status                          |
| --- | ------------------------------------------------ | -------- | ------------------------------- |
| 1   | NULL-root ghost deleted after backfill+sweep     | Critical | Zero coverage                   |
| 2   | LIKE prefix vs relative paths (silent no-op bug) | Critical | Zero coverage + likely real bug |
| 3   | Boundary `durationMs == 2000`                    | Low      | Zero coverage                   |

---

## Round 1 — MPD protocol coverage

**Server disabled in all tests.** `HttpIntegrationTestBase.kt:46` hardcodes `yaytsa.mpd.enabled=false`. No MPD tests exist. Zero coverage of the command-list changes.

### Gap 1 — `command_list_ok_begin` multi-command happy path (CRITICAL)

The just-changed `handleClient` loop (MpdTcpServer.kt:101–120) strips `OK\n` per-command and appends `list_OK\n` in ok-mode. This is the primary change and is completely untested. Per [MPD protocol spec](https://mpd.readthedocs.io/en/stable/protocol.html): each sub-command gets its payload + `list_OK`, then a single `OK` closes the batch.
Testability: raw TCP socket against a `MpdTcpServer` started on a random port with `yaytsa.mpd.enabled=true`.

### Gap 2 — `command_list_begin` (plain) no `list_OK` (HIGH)

Plain `command_list_begin` must concatenate payloads and emit a single `OK` with no `list_OK` separators. The `commandListOkMode=false` branch is untested — a regression here would silently break ncmpcpp.

### Gap 3 — Failing sub-command stops batch, emits `ACK`, no trailing `OK` (CRITICAL)

MpdTcpServer.kt:110–113: on `ACK`, sets `failed=true` and breaks. The final `if (!failed)` skips the `OK`. Correct per spec — but entirely untested. A bug here (e.g. always appending `OK`) would cause clients to hang waiting for end of response.

### Gap 4 — `command_list_ok_begin` / `command_list_end` handled by `MpdCommandHandler.handle()` (BUG)

MpdCommandHandler.kt:106: `"command_list_begin", "command_list_ok_begin", "command_list_end" -> ok()` — these are supposed to be intercepted by `MpdTcpServer` before reaching the handler. If a client sends `command_list_ok_begin` as a raw command (e.g. nested or malformed), the handler returns `OK\n` silently, masking the protocol error.

### Gap 5 — `idle` hardcoded to `changed: player` only (HIGH)

`blockingIdle` (MpdTcpServer.kt:71): always emits `changed: player\nOK\n` on any state change. MPD spec defines subsystem names (`player`, `playlist`, `mixer`, `options`, `database`, etc.). Clients like Symfonium filter by subsystem. No test for `idle player` vs `idle playlist` distinction, nor for `noidle` cancellation.

### Gap 6 — `setvol/clear/add/repeat/random` now correctly return `ACK` (FIXED, but untested) (HIGH)

MpdCommandHandler.kt:74–75: these now `ack(5, cmd, "command not supported…")` instead of lying with `ok()`. This is a correctness fix — but it's not tested. Any regression back to the old lie would be silent.

### Testability recommendation

Add a `MpdIntegrationTest` using `@SpringBootTest(webEnvironment=RANDOM_PORT)` with `yaytsa.mpd.enabled=true` and a random `yaytsa.mpd.port`. Connect via `java.net.Socket`, skip the `OK MPD 0.23.5` banner, send command-list sequences as raw text, assert line-by-line. Testcontainers (already used in the project) provides the PostgreSQL dependency.

Sources:

- [MPD Protocol — stable](https://mpd.readthedocs.io/en/stable/protocol.html)
- [MPD Protocol — latest/git](https://mpd.readthedocs.io/en/latest/protocol.html)
- [Mopidy command_list module](https://docs.mopidy.com/en/release-0.19/_modules/mopidy/mpd/protocol/command_list/)
- [MPD Client Developer's Manual](https://mpd.readthedocs.io/en/latest/client.html)

---

## Round 1 — Frontend SW/infinite-query coverage

### Gap 1 — Service Worker autoUpdate / ChunkLoadError reload (CRITICAL)

**No coverage exists.** All 13 e2e specs were grepped for `serviceWorker`, `registerSW`, `offline`, `pwa`, `reload` — zero hits.

**Scenario**: Tab is open on deploy N. Deploy N+1 lands. SW fires `controllerchange`. `registerSW({immediate:true})` calls `location.reload()`. The tab must survive without ChunkLoadError or blank screen.

**Testability**: Playwright can simulate this with `page.addInitScript` to stub `navigator.serviceWorker` events, or by using Playwright's service-worker interception (`context.on('serviceworker', …)`). The [vite-pwa-org testing guide](https://vite-pwa-org.netlify.app/guide/testing-service-worker) confirms Playwright (Chromium only) is the intended vehicle — it requires a real production build served locally, not the Vite dev server (SW is disabled in dev). The vite-plugin-pwa repo has example Playwright configs in `/examples/`.

**Severity**: HIGH. An autoUpdate SW that crashes on reload defeats its purpose. This is observable production behavior with zero test signal.

---

### Gap 2 — Infinite query offset correctness + maxPages eviction (HIGH)

**Existing coverage**: `library.spec.ts:36` ("should load more albums on scroll") only checks `newCount > initialCount`. It hits a real server with real data and never asserts **which** items appear at which position.

**Two untested invariants from `useInfiniteLibraryQuery.ts:54-62`**:

1. **Offset correctness after eviction**: After scrolling past 10 pages (`maxPages=10`), page 1 is evicted. Scrolling back up triggers `getPreviousPageParam` → refetch at offset 0. If `lastPageParam` math were wrong (e.g., using `allPages.length * limit` instead of the param), offset 0 would re-fetch the wrong slice → duplicate or skipped tracks visible to the user.

2. **No duplicate/skip on forward scroll across the eviction boundary**: At page 11 the param must equal `500` (10 × 50), not `allPages.length * limit` which would be `0` (only 10 pages retained). The implementation looks correct but is **untested**.

**Concrete scenario**: User scrolls a 50k library 11 times (550 tracks loaded). Tracks at positions 0–49 should match tracks at positions 0–49 when scrolling back up. If they don't, the user hears a different album than they selected.

**Testability with `page.route`**:

```
page.route('**/Items**', (route) => {
  const url = new URL(route.request().url());
  const startIndex = Number(url.searchParams.get('StartIndex') ?? 0);
  const limit = Number(url.searchParams.get('Limit') ?? 50);
  // deterministic items: id = startIndex + i
  route.fulfill({ json: { Items: range(limit).map(i => ({Id: `${startIndex+i}`})), TotalRecordCount: 1000 } });
});
// scroll down 11× → collect all rendered IDs → assert no duplicates (Set size === array length)
// scroll back up → assert IDs at top match startIndex=0 items
```

[Playwright infinite scroll testing pattern](https://wanago.io/2024/04/29/react-infinite-scrolling-e2e-playwright/) and [TanStack maxPages docs](https://tanstack.com/query/v5/docs/react/reference/useInfiniteQuery) confirm this is the right approach for offset-based pagination.

**Severity**: HIGH. Duplicate or skipped tracks in the library is a silent data correctness bug that no existing test catches.

---

### Files requiring new tests

- `apps/web/tests/e2e/library.spec.ts` — add maxPages eviction + offset correctness test
- `apps/web/tests/e2e/pwa.spec.ts` (new) — SW autoUpdate reload lifecycle (requires `webServer` pointing at `npm run preview`)

Sources:

- [TanStack Query v5 useInfiniteQuery](https://tanstack.com/query/v5/docs/react/reference/useInfiniteQuery)
- [TanStack maxPages example](https://tanstack.com/query/latest/docs/framework/react/examples/infinite-query-with-max-pages)
- [vite-pwa testing guide](https://vite-pwa-org.netlify.app/guide/testing-service-worker)
- [vite-plugin-pwa autoUpdate](https://vite-pwa-org.netlify.app/guide/auto-update)
- [Playwright infinite scroll testing](https://wanago.io/2024/04/29/react-infinite-scrolling-e2e-playwright/)
- [Playwright pagination mock](https://medium.com/@iragantiganesh555/testing-infinite-scroll-and-pagination-in-playwright-f3c8b1f61c9a)

---

## Round 1 — E2E user-flow coverage

### What exists

- **Login/logout**: `auth.spec.ts` covers UI-level logout (redirect to login page, form cleared). No assertion that the revoked token returns 401 on subsequent API calls — the server-side token invalidation is untested end-to-end.
- **Favorites**: `favorites.spec.ts` covers toggle + persistence across navigation + tab display. Does NOT test the batch-load path (large library page showing favorites status for 100+ items at once).
- **Playback/seek**: `playback.spec.ts` covers seek forward, invalid seek recovery, auto-advance. No HTTP 206 response validation or Range header verification — browser abstraction hides streaming layer.
- **Karaoke**: zero coverage across all specs.
- **Multi-user isolation**: zero coverage — no two-user fixture exists.
- **Subsonic protocol**: no integration tests whatsoever.

### Critical gaps

**1. Token revocation post-logout (Severity: HIGH)**
Journey: login → act → logout → reuse saved token against API → expect 401.
Protects: the `Sessions/Logout` endpoint + Caffeine cache eviction in the auth bounded context.
Testability: `packages/core` integration test — call `client.logout()`, then craft a raw fetch with the captured token; assert 401.
Reference: [Playwright auth docs — validateFn pattern](https://playwright.dev/docs/auth) shows server-side validation is the missing layer client-only isolation cannot cover.

**2. Multi-user IDOR isolation (Severity: HIGH)**
Journey: admin creates user B → user A logs in, obtains user B's userId → GETs `/v1/users/{B}/preferences` → must receive 403.
Protects: the IDOR fix from the recent correctness audit; admin create-user PascalCase body change (commit ee5b3a06).
Testability: `packages/core` integration test with two live accounts (admin + regular), assert cross-user preference/favorite reads are rejected.

**3. Karaoke end-to-end (Severity: MEDIUM)**
Journey: play track → toggle karaoke → poll `/Karaoke/{trackId}/status` until `ready` → stream instrumental (206) → seek mid-track → switch to vocals stem.
Protects: range-clamp fix in `feature-extractor-cronjob.yaml` and audio-separator sidecar wiring.
Testability: Playwright E2E — requires a pre-separated track in the test fixture; network intercept to verify `Content-Range` header on seek.
Reference: [HTTP range requests — MDN](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Range_requests) details what a correct 206 + `Content-Range` response must contain.

**4. Favorites batch-load at scale (Severity: LOW)**
Journey: library with 200+ tracks → navigate to Favorites page → all items render with correct heart state without N+1 calls.
Protects: batch-loaded favorites path (recently changed values.yaml embedding extractor config affects library size in CI).
Testability: Playwright E2E — intercept `/Items` requests, count them, assert ≤2 total for full favorites page render.

**5. Subsonic browse → stream → cover art (Severity: MEDIUM)**
Journey: OpenSubsonic client `ping` → `getMusicFolders` → `getSong` → `stream` (206) → `getCoverArt`.
Protects: adapter-opensubsonic which has no integration test layer at all.
Testability: `packages/core` integration test hitting `/rest/*` endpoints with token auth; assert XML response shape + 206 on stream.

### Partial coverage note

Seek tests in `playback.spec.ts` move the scrubber but never assert the HTTP layer — a server-side seek bug (wrong `Content-Range`, no `Accept-Ranges` header) would pass all current tests silently.

---

## Round 1 — Regression-risk of new code

### 1. WebSocketNotificationPublisher per-user routing — MEDIUM risk, UNTESTED

`WebSocketNotificationPublisher.publish()` now dispatches to `/user/{userId}/queue/{context}` when payload contains a `userId` field, and to `/topic/{context}` otherwise. The PWA is SSE-based (no STOMP subscription), so direct delivery to WebSocket user-queues is silently dropped for PWA clients. There is zero integration test asserting any notification reaches a subscriber endpoint. **Scenario**: preference-change notification carries `userId` → routes to `/user/…/queue/preferences` → PWA receives nothing → stale preferences displayed. No test covers this path.

### 2. TokenValidationCache 10s TTL after DeactivateUser/ChangePassword — HIGH risk, UNTESTED

`JellyfinAdminController.DeactivateUser` (line 118) and `ChangePassword` (line 141) execute domain commands that mutate `UserAggregate` in the DB, but publish **no** `TokenRevokedEvent`. `TokenValidationCache.onTokenRevoked` only fires on explicit `/Sessions/Logout` (line 185, `JellyfinAuthController`). Result: after an admin deactivates a user or changes their password, the cached `UserAggregate` remains valid for up to 10s, and `ApiTokenAuthFilter` re-checks `isActive` against the **cached** (stale) aggregate — granting continued streaming. Before this cache was introduced, every request hit the DB, making revocation immediate. This is a confirmed behavioral regression from the pre-cache baseline. `AuthIntegrationTest` has no test for "deactivated user token rejected immediately."

### 3. Favorites `TotalRecordCount` = `favorites.size` including vanished tracks — MEDIUM risk, UNTESTED

`JellyfinItemsController` line 110 returns `favorites.size` (raw preference aggregate list including deleted/vanished tracks) as `TotalRecordCount`, while `items` contains only resolved tracks. PWA pagination uses `TotalRecordCount` to determine when infinite-scroll stops. If 3 of 10 favorites point to deleted files, count=10 but items=7 — PWA requests page 2 that never arrives, producing a broken scroll. `ItemsPaginationIntegrationTest` asserts sort order only; no test inserts a dangling favorite and asserts the count equals resolved items.

### 4. BCrypt cost 10→13 — LOW risk, SAFE BY DESIGN, but test gap

Production passwords hashed at cost 10 (default `gensalt()`) still authenticate: `BCrypt.checkpw` reads cost from the `$2a$10$…` prefix and iterates accordingly — cost in `gensalt()` affects only new hashes. `AuthIntegrationTest` seeds test users with `gensalt()` (default cost 10, lines 80/117/146/204/259), not `gensalt(13)`. This confirms existing users are unaffected, but there is **no test** that explicitly seeds a cost-10 hash and verifies login succeeds after the constant changed to 13. A future refactor that mistakenly passes cost as a `checkpw` argument would silently break login for all existing users with no test catching it.

### 5. Idempotency replay returns current aggregate, not stored original — LOW risk, UNTESTED

Per CLAUDE.md: "Same key, same payload → returns exact same `CommandResult` as first execution." No integration test in `app/` validates this contract. A regression where replay returns the _current_ aggregate (which may have been mutated since) would be invisible.

### Sources

- [BCrypt hash includes cost prefix — Spring Docs](https://docs.spring.io/spring-security/site/docs/current/api/org/springframework/security/crypto/bcrypt/BCrypt.html)
- [BCrypt cost migration — medium post on old-hash compatibility](https://blog.devgenius.io/we-hashed-passwords-with-bcrypt-10-took-2-days-to-migrate-to-12-6e72bc16ec6b)
- [Auth security mistakes — stale token issue](https://securityboulevard.com/2026/04/12-authentication-api-security-mistakes-developers-still-make-in-2026/)

---

## Round 2 — Real bugs vs test gaps

### Fix #1 — ghost-row sweep (`findTrackIdSourcePathsByLibraryRootOrNull`) — CORRECT

`LibraryEntityRepository.kt:128-134` selects `id, source_path` for `entity_type='TRACK' AND (library_root=:root OR library_root IS NULL)`. `LibraryWriter.kt:427-431` filters out rows whose `source_path ∈ presentSourcePaths`, deletes the rest. `LibraryScanner.kt:47` builds `presentSourcePaths` via `rootPath.relativize(file).toString()` — **relative**, exactly matching the relative `source_path` column. So: NULL-root ghost with relative path absent on disk → deleted; present → kept. Reconcile is gated on `walkCompleted` (`:62`) so a failed walk can't mass-delete. Logic is correct. The old LIKE-prefix no-op is gone. (TEST-GAP only: no Postgres integration test exercises the NULL-root branch — Round1 library Gap1/4 stand.)

### Fix #2 — `UserSecurityChangedEvent` / `invalidateUser` — PARTIALLY CORRECT; one REAL BUG

Wiring is sound: `JellyfinAdminController.kt:123,148` publish `UserSecurityChangedEvent(uid)`; `TokenValidationCache.kt:54-57` listens and `invalidateUser` (`:42-44`) removes every cache entry whose `value.id.value == userId`. ApiTokenAuthFilter re-checks `user.isActive`/`revoked`/`expired` on the (now re-fetched) aggregate (`ApiTokenAuthFilter.kt:30,36`). For **DeactivateUser** this fully fixes Round1 Gap3: post-eviction re-fetch returns `isActive=false` → 401. CORRECT.

**REAL BUG — ResetPassword does not revoke old tokens.** `JellyfinAdminController.kt:147` comment claims "old sessions can't keep authenticating post-reset," but `AuthHandler.changePassword` (`AuthHandler.kt:72-87`) only rewrites `passwordHash` — it never revokes/clears `apiTokens`. Cache eviction merely forces a DB re-fetch, and the re-fetched aggregate still carries every non-revoked token. So an attacker's existing bearer token keeps authenticating indefinitely after an admin password reset. This violates OWASP ASVS V3 (password change must invalidate other sessions). The eviction is a no-op for the stated security goal. **File:line:** `JellyfinAdminController.kt:142-148` + `AuthHandler.kt:72-87`. Fix: have `ChangePassword`/a new `RevokeAllTokens` clear `apiTokens` in the aggregate.

**Secondary (TEST-GAP / latent):** `@EventListener` is synchronous and fires inside the publisher's call path; eviction can occur before the use-case transaction commits, so a concurrent in-flight request may re-cache the pre-commit (stale) aggregate, re-staling for up to the 10s TTL. `@TransactionalEventListener(AFTER_COMMIT)` would close this. Low severity (10s window); flag as hardening.

### Other R1 findings — scan for additional shipped bugs

- **Idempotency `ON CONFLICT DO NOTHING`** (`JpaIdempotencyStore.insertIfAbsent`): app-layer `find()`-before-`store()` still enforces same-key/different-payload → InvariantViolation. No code bug; TEST-GAP (no Postgres-wired test) — Round1 idempotency Gaps 1-5 are gaps, not bugs.
- **`getTracksByIds` drops vanished IDs** (`JpaLibraryQueryPort.kt:156-163`): silently drops deleted track rows; `TotalRecordCount` still = `favorites.size`. This is the intended favorites behavior, not a crash. TEST-GAP.
- **Karaoke RFC 7233 clamp / Subsonic clamp**: `minOf(requestedEnd, fileSize-1)` is correct clamping. TEST-GAP (no HTTP regression test) — FALSE-POSITIVE as bug.

**Verdict:** Fix #1 CORRECT. Fix #2 fixes deactivation but leaves a **REAL BUG**: password reset never revokes existing tokens.

Sources:

- <https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html>
- <https://github.com/OWASP/ASVS/blob/master/4.0/en/0x12-V3-Session-management.md>
- <https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html>
- <https://github.com/spring-projects/spring-framework/issues/23192>

---

## Round 2 — ROI test ranking

Scope: pin the _just-changed_ robustness code. Ranked by (user impact × regression likelihood ÷ effort). Backend Testcontainers = fast (HTTP base + JDBC seed already proven). STOMP/Playwright = heavy.

| #   | Test (behavior pinned)                                                                                                                                                                                      | Base                                                       | Effort | Type          |
| --- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------- | ------ | ------------- |
| 1   | **IDOR `/v1/users/{id}/preferences`** — A→B GET/PUT = **403**, self GET=200/PUT=204. Pins `JellyfinAdaptiveController:251,279`.                                                                             | ★ AuthLogout (2-user/token seed already there)             | S      | Backend TC    |
| 2   | **IDOR `GET /Users/{id}`** — non-admin A→B=403, admin→B=200. Pins `JellyfinAuthController:111`.                                                                                                             | ★ AuthLogout / JellyfinApiIntegrationTest                  | S      | Backend TC    |
| 3   | **Stale-cache deactivation bypass** — login→`DeactivateUser`→re-request inside 10s TTL = **401**. Confirmed regression (cache lacks event). Pins `TokenValidationCache`.                                    | ★ AuthLogout                                               | S–M    | Backend TC    |
| 4   | **Idempotency `ON CONFLICT` no-op** — `store()` twice same key → no throw, `find()` returns original; concurrent 2× = both succeed. Native SQL never run vs Postgres.                                       | new `IdempotencyStorePersistenceTest`                      | S      | Backend TC    |
| 5   | **Artist pagination slice + TotalRecordCount** — 6-track fixture, `ParentId=artist&Recursive&Limit=2&StartIndex=2` → 2 items, total=6, disc/track order. Pins `browseTracksByArtist` native SQL.            | ★ ItemsPagination                                          | S–M    | Backend TC    |
| 6   | **Favorites batch w/ vanished track** — 3 favs, delete 1 row, `/Items?IsFavorite=true` → `TotalRecordCount` matches resolved items, surviving order intact. Pins `getTracksByIds` mapNotNull + count.       | ★ ItemsPagination                                          | M      | Backend TC    |
| 7   | **Karaoke RFC 7233 clamp** — `/Karaoke/{id}/instrumental`: end-past-EOF→206 clamped; `bytes=-100` suffix→206; start-past-EOF→**416** `*/size`; no Range→200; missing→404. Primary changed code, zero guard. | ★ AudioStream (temp file + JDBC asset seed)                | M      | Backend TC    |
| 8   | **WebSocket per-user isolation** — 2 STOMP clients, A's `PlaybackStateChanged(userId=A)` reaches `/user/queue/playback` for A only, **not** B; bad token CONNECT rejected. Privacy defect.                  | new `WebSocketStompIntegrationTest`                        | L      | STOMP (heavy) |
| 9   | **Subsonic stream clamp + getCoverArt round-trip** — `/rest/stream?bytes=0-99999999`→206 clamped (not 416); `getAlbum`→`coverArt` id→`getCoverArt`→bytes+content-type. Pins `MediaStreamController:92`.     | ★ AudioStream                                              | M      | Backend TC    |
| 10  | **MPD `command_list_ok_begin`** — raw TCP, multi-cmd batch emits `list_OK` per sub-cmd + closing `OK`; failing sub-cmd→`ACK`, no trailing `OK`. Pins `MpdTcpServer:101-120`.                                | new `MpdIntegrationTest` (`mpd.enabled=true`, random port) | M      | Backend TC    |

**Implement-now cluster (cheapest, highest certainty): 1, 2, 3, 4, 5, 7** — all backend Testcontainers, six extend an existing base (★) except #4, near-zero scaffolding, guard confirmed/changed behavior on the daily path. Do these first.

**Defer:** #8 (STOMP harness setup cost) and #10 (new raw-TCP harness) are high-value but L effort — second wave. Ghost-row backfill (R1 GAP-2 flags a likely **real bug**: `LIKE '/root%'` never matches _relative_ `source_path` → backfill is a silent no-op) deserves its own diagnosis test but is autoqa-invisible corruption, so M priority after the auth cluster. Frontend SW-reload + infinite-query `maxPages` are Playwright (heaviest) — lowest ROI here.

Sources:

- [OWASP WSTG — Testing for IDOR](https://owasp.org/www-project-web-security-testing-guide/latest/4-Web_Application_Security_Testing/05-Authorization_Testing/04-Testing_for_Insecure_Direct_Object_References)
- [Equixly — API Authorization Matrix testing (2025)](https://equixly.com/blog/2025/10/07/authorization-matrix/)
- [Spring — STOMP User Destinations](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/user-destination.html)
- [Baeldung — Spring WebSockets send message to user](https://www.baeldung.com/spring-websockets-send-message-to-user)

---

## Round 2 — Severity calibration (deployment-aware)

Context: single user / small family on the React PWA (Jellyfin). Subsonic/MPD/MCP are convenience surfaces. autoqa = post-deploy status only (blind to silent data/library corruption). Multi-user matters only because `IsAdmin` RBAC ships and a family may have 2–3 accounts.

**Calibration rule applied:** firm on (a) corruption of the user's own library, (b) a non-admin/deactivated/cross-user account acting, (c) PWA daily browse/play/favorite breakage. Ruthless down-rate on multi-replica, concurrent-same-user, and protocol surfaces nobody touches daily.

### 🔴 Would actually bite this deployment

- **Scanner LIKE-prefix backfill is a no-op for relative paths (P3 ghost GAP 2).** `source_path LIKE '/media/music%'` never matches relative `Artist/Album/x.flac`; ghost rows with NULL `library_root` are never adopted/swept → permanent phantom tracks in the user's OWN library, autoqa-invisible. Confirmed logic bug, not just a gap.
- **IDOR cross-user preferences/`/Users/{id}` (P3 Auth Gap 1+2).** RBAC ships; a family's non-admin reading/writing another's prefs is exactly the harm BOLA tops OWASP API1:2023 for. Check is in prod but _zero_ tests — a refactor silently re-opens it. Firm 🔴 (it's the stated reason the fix matters).
- **Favorites `TotalRecordCount` counts vanished tracks (P3 pagination Gap 4 / regression #3).** count=10 / items=7 → PWA infinite scroll requests a page that never arrives → broken Favorites view on the daily path. Combined with the scanner ghost bug, dangling favorites are likely, not theoretical.
- **Search `Limit` uncapped (Pass-2 confirmed).** `?Limit=1e6` from the user's own client = self-DoS / huge payload. Trivial fix, daily-path surface.

### 🟡 Real but bounded / narrow blast radius

- **Deactivated user authenticates within 10s cache TTL (P3 Auth Gap 3 / regression #2).** Genuine bypass _window_ but bounded to 10s — unlike CVE-2026-34572 (CI4MS) which retains access _indefinitely_. For a 2–3-person family, down-rate to 🟡; would be 🔴 in a hostile multi-tenant system.
- **Karaoke stem range-clamp untested (P3 streaming Gap 1).** Just-changed RFC 7233 logic, no guard; karaoke is a kept feature, but seek-in-stem is occasional, not daily. 🟡.
- **Artist recursive pagination native SQL untested (P3 pagination Gap 1–3).** Daily browse path; LIMIT/OFFSET on real Postgres never fired in CI. 🟡 (likely works, but daily-path → guard it).
- **Idempotency `ON CONFLICT` native query unvalidated on Postgres (P3 idem Gap 1).** Single-user rarely double-submits, but a broken native query fails at runtime invisibly. 🟡.
- **SW autoUpdate reload / infinite-query offset-after-eviction (frontend).** ChunkLoadError on deploy or duplicate/skipped tracks on long scroll = visible PWA breakage. 🟡 (eviction needs 500+ scrolled tracks).
- **MPD `command_list`/ACK, MCP cast→500.** Convenience surfaces; cheap to guard. 🟡.

### 🔵 Theoretical for this deployment

- **WebSocket per-user isolation / CONNECT rejection (P3 WS Gaps).** PWA is SSE-based, not STOMP — these queues are effectively dead today. Down-rate hard.
- **Concurrent same-key idempotency race, outbox SKIP-LOCKED double-poll, SpringExecutor atomicity (P3 idem Gap 2/5).** One human can't race themselves; single replica. 🔵.
- **Subsonic open-ended 206 / download / coverArt; cross-protocol consistency.** Symfonium/Finamp only, not daily. 🔵.
- **BCrypt 10→13 compatibility, durationMs==2000 boundary.** Safe-by-design / cosmetic. 🔵.

**Net:** the autoqa-blind, user-biting frontier is 4 reds — scanner relative-path backfill (own-library corruption), IDOR (RBAC), favorites count (daily scroll), search cap (self-DoS). The deactivated-user window is real but 10s-bounded → 🟡, not the indefinite-access CVE class.

Sources:

- [OWASP API1:2023 Broken Object Level Authorization](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/)
- [BOLA impact & prevention (Pynt)](https://www.pynt.io/learning-hub/owasp-top-10-guide/broken-object-level-authorization-bola-impact-example-and-prevention)
- [CI4MS deactivated accounts retain indefinite access — CVE-2026-34572 (TheHackerWire)](https://www.thehackerwire.com/ci4ms-deactivated-accounts-retain-indefinite-access/)
- [MITRE D3FEND — Authentication Cache Invalidation](https://d3fend.mitre.org/technique/d3f:AuthenticationCacheInvalidation/)

---

## Round 2 — Missed gaps

R1 (Pass-3) was thorough but rests on a **stale read of two changed paths** and skips several edge interactions. Confirmed against real code:

**STALE/WRONG in R1 — `UserSecurityChangedEvent` already exists.** R1 Gap-3 (auth, line 207) and Regression #2 (line 641) both assert deactivate/change-password publish _no_ event → "confirmed behavioral regression," 10 s stale auth. False. `JellyfinAdminController.kt:123,148` publish `UserSecurityChangedEvent`; `TokenValidationCache.onUserSecurityChanged` → `invalidateUser` (`TokenValidationCache.kt:42-57`). The real gap is **`invalidateUser` is untested**: it does `cache.asMap().entries.removeIf { it.value.id.value == userId }` — Caffeine's `asMap()` is a weakly-consistent view; iterating-while-`put` (an in-flight `findByApiToken` for the same user) can let a just-re-cached entry survive eviction. No test seeds two tokens for one user, deactivates, and asserts _both_ are evicted. 🟡

**Karaoke clamp when `fileSize == 0` — untested boundary.** `JellyfinKaraokeController.kt:131-161`: a zero-byte stem (worker wrote an empty/truncated file) makes `start >= fileSize` → `0 >= 0` true for _every_ range, including `bytes=0-` → 416 with `bytes */0`. RFC 7233 confirms 416 + `*/0` is correct, but no test covers an empty stem, and no-Range on a 0-byte file falls to the 200 branch with `Content-Length: 0`. The just-added clamp has zero karaoke HTTP tests at all (R1 Streaming Gap-1 noted this; the `fileSize==0` corner is additionally unguarded). 🟡

**Per-user routing: two devices, one account.** `WebSocketNotificationPublisher.kt:19` `convertAndSendToUser(userId,…)`. Spring's default `broadcast=true` delivers to **all** STOMP sessions sharing that principal name — so both of a user's devices receive `PlaybackStateChanged`. That is _intended_ for multi-device sync, but it is the exact opposite assertion R1 WebSocket Gap-1/5 frames ("arrives only to UserA"). The untested risk is the inverse: a payload `userId` for user A must NOT reach user B's session, **and** must reach _every_ A session. No test pins either half. 🔴 (correctness + privacy).

**`extractUserId` numeric `userId`.** `WebSocketNotificationPublisher.kt:30` `.takeIf { it.isTextual }`. If an outbox payload ever serializes `userId` as a JSON number (it is a UUID string today, but nothing enforces that at the outbox boundary), `isTextual` is false → falls back to `/topic/$context` **broadcast to everyone** — a silent fan-out leak. Untested. 🟡

**`browseTracksByArtist` coercion.** `JpaLibraryQueryPort.kt:150` `maxOf(limit,1)`, `maxOf(offset,0)`. Controller already coerces `limit.coerceIn(1,MAX)` (`JellyfinItemsController.kt:119`), so the port's `maxOf(limit,1)` is **dead defensive code reachable only via a non-HTTP caller** — `limit=0` would silently return 1 row, not 0. Negative offset → 0. Double-coercion, neither layer tested. 🔵

**`getCoverArt` path escaping `safeRoot`.** `MediaStreamController.kt:39-43`: `image.path` comes straight from the DB (scanner-written) and is fed to `MediaPathSafety.resolveServableFile`. If a malicious/buggy `images.path` row points outside `safeRoot` (or is a symlink — guarded by `NOFOLLOW_LINKS` at `MediaPathSafety.kt:21`), it returns 404. The containment guard (`real.startsWith(safeRoot)`, `:32`) is **never exercised by any test** for the cover-art or karaoke entry points — only (per R1) the stream path is partially covered.

**`getTracksByIds` double short-circuit.** Empty-list guard exists in _both_ `LibraryQueries.kt:50` and `JpaLibraryQueryPort.kt:157`. Favorites page past end (`StartIndex >= favorites.size`) → `pageFavs` empty → empty result, yet `TotalRecordCount = favorites.size` (`JellyfinItemsController.kt:110`) → PWA infinite-scroll never terminates. Untested interaction. 🟡

**Idempotency `insertIfAbsent` constraint match — VERIFIED safe.** `ON CONFLICT (user_id, command_type, idem_key)` (`IdempotencyRecordJpaRepository.kt`) matches the `PRIMARY KEY (user_id, command_type, idem_key)` in `V001__shared_schema.sql:10`. No mismatch bug — but R1 Gap-1 stands: zero Postgres-wired test fires this native query.

Sources:

- <https://www.rfc-editor.org/rfc/rfc7233> (416 + `Content-Range: bytes */0` for zero-length)
- <https://docs.spring.io/spring-framework/reference/web/websocket/stomp/user-destination.html> (convertAndSendToUser broadcasts to all sessions of a principal by default)

---

## Round 2 — Harness feasibility recipes

All recipes verified against real files. Two harnesses: `HttpIntegrationTestBase` (`@SpringBootTest @AutoConfigureMockMvc`, shared `pgvector/pgvector:pg16` Testcontainer, `init-extensions.sql`) for HTTP; `LibraryWriterHygieneTest` (own container, `@SpringBootTest(classes=[ScannerTestApplication])`, flyway `db/library`) for scanner. NOTE: base sets `flyway.enabled=false`+`ddl-auto=none` (lines 43-44) yet existing green tests insert into `core_v2_*` — schema IS present in practice (reuse their exact INSERTs). The HTTP seed boilerplate is identical in every test (GroupSync/Playlists/AudioStream/ItemsPagination): no shared `seedUser()` helper exists — each copies `authUseCases.execute(CreateUser(uid, name, "testpassword", "Test", null, isAdmin), CommandContext(...AggregateVersion.INITIAL))` then `CreateApiToken(uid, ApiTokenId(...), token, DeviceId("test"), "Test", null)` at `AggregateVersion(1)`. Request helpers: `post(url, body, token)`, `get(url, token)`, `delete(url, token)` — token goes in `Authorization: Bearer`.

**1. IDOR (cross-user 403).** Endpoint exists: `JellyfinAdaptiveController.kt:246 @GetMapping("/users/{userId}/preferences")`, `:251 if(principal.name != userId) return 403`; PUT at `:273`. Recipe: seed userA(idA,tokenA) + userB(idB) using the copy-paste block twice (distinct UUIDs). Then `get("/v1/users/$idB/preferences", tokenA)` → assert 403; `get("/v1/users/$idA/preferences", tokenA)` → 200; `put` via `mockMvc.perform(MockMvcRequestBuilders.put(...).header("Authorization","Bearer $tokenA").contentType(JSON).content(...))`. `HttpIntegrationTestBase` has no `put` helper — call `mockMvc` directly. Admin/`GET /Users/{id}` (`JellyfinAuthController.kt:111`) uses `CreateUser(...isAdmin=true...)` (6th arg).

**2. Idempotency ON CONFLICT.** Two layers. (a) Persistence: new `IdempotencyStorePersistenceTest` in `infra-persistence/shared/src/test/...` extending `core-testkit/.../AbstractPersistenceTest`; autowire `JpaIdempotencyStore`; call `store()` twice same key, assert no throw + `find()` returns original. (b) HTTP: header constant `IDEMPOTENCY_KEY_HEADER="Idempotency-Key"` (`AdapterCommandContextFactory.kt:43`). In a `HttpIntegrationTestBase` test add `.header("Idempotency-Key", key)` to a state-mutating POST; replay same key+body → identical result; same key+different body → 4xx InvariantViolation.

**3. /Items artist pagination.** Pure `jdbc.update`, no auth needed beyond token. Pattern from `ItemsPaginationIntegrationTest.kt:46-57` + `AudioStreamIntegrationTest.kt:56-69`. Seed artist: `INSERT INTO core_v2_library.entities(id,entity_type,name,sort_name,search_text) VALUES(?,'ARTIST',...)` + `INSERT INTO core_v2_library.artists(entity_id) VALUES(?)`. Tracks link via `audio_tracks(entity_id,codec,duration_ms)` + an `album_artist_id`/`album_id` FK column (verify in `core_v2_library` track schema). Then `get("/Items?ParentId=$artistId&Recursive=true&Limit=2&StartIndex=2", token)`, assert `TotalRecordCount`+`Items.size`.

**4. Karaoke range clamp.** Controller `JellyfinKaraokeController.kt:99 streamStem`, clamp at `:157 if(start<0||start>=fileSize||requestedEnd<start)→416`, `:162 end=minOf(requestedEnd,fileSize-1)`. Schema `core_v2_karaoke.assets(track_id PK, instrumental_path, vocal_path, lyrics_timing, ready_at)`. Recipe (mirror AudioStream): `Files.createTempFile(...,".flac"); Files.write(file, ByteArray(2048))`; seed track entity+audio_tracks; `jdbc.update("INSERT INTO core_v2_karaoke.assets(track_id,instrumental_path,vocal_path,ready_at) VALUES(?,?,?,now())", trackId, file.toAbsolutePath().toString(), null)`. NOTE base sets `yaytsa.karaoke.enabled=false` (line 49) — controller `/enabled` gates, but `streamStem` reads `karaokeQueryPort.getAsset`; if enabled-flag blocks the route, override via `@TestPropertySource("yaytsa.karaoke.enabled=true")`. Then `get("/Karaoke/$trackId/instrumental", token)` with `Range: bytes=0-99999` → 206 + `Content-Range: bytes 0-2047/2048`; `bytes=3000-`→416; `bytes=-100`→206; no Range→200.

**5. Ghost-row sweep.** `LibraryWriter.kt:418 deleteVanishedTracks(root: Path, presentSourcePaths: Set<String>): Int`. Test in `LibraryWriterHygieneTest` (has `jdbc`+`writer`+`entityRepo`, `@BeforeEach` truncates). Seed NULL-root ghost: `jdbc.update("INSERT INTO core_v2_library.entities(id,entity_type,name,sort_name,source_path,library_root) VALUES(?,'TRACK',?,?,?,NULL)", id,"X","x","Artist/Album/t.flac")` + matching `audio_tracks`. Call `writer.deleteVanishedTracks(root, emptySet())`, assert `entityRepo.count()==0`. NOTE: comment at `:423-426` says source_path is RELATIVE so backfill-by-prefix can't attribute NULL rows — the present-paths filter (`:430`) deletes NULL rows absent from disk. Test confirms this contract directly.

Sources:

- <https://www.baeldung.com/spring-boot-testcontainers-integration-test> (JdbcTemplate seeding + Testcontainers Postgres)
- <https://www.baeldung.com/spring-oauth-testing-access-control> (MockMvc 403/cross-user authorization assertions)
- <https://datatracker.ietf.org/doc/html/rfc7233> (range clamp / 416 / suffix-range expected behavior)

---

## Round 3 — Prosecution

The orchestrator fixed three real bugs and pinned them (RemediationSecurityIntegrationTest: 5 tests — IDOR prefs read/write, non-admin profile, deactivation-vs-cache, password-reset token revoke; plus ArtistPagination, IdempotencyStoreConflict, ghost-row). Green. But the changeset's **largest just-changed surface is still untested**, and three gaps are not theoretical for this PWA deployment.

**1. Karaoke RFC 7233 clamp — ZERO HTTP tests (HIGH).** `JellyfinKaraokeController.kt:157-165` is the _primary_ range logic added this changeset: 416 only on `start>=fileSize`, else `end=minOf(requestedEnd,fileSize-1)`, plus a suffix-spec branch (`:142`). `AudioStreamIntegrationTest` has exactly 3 tests (ALAC 206, WMA, Subsonic-416) and `RemediationSecurityIntegrationTest` has none touching karaoke. Untested: end-past-EOF→206-clamped, `bytes=-100` suffix, start-past-EOF→416 `*/size`, and the **zero-byte-stem** corner (`fileSize==0` → every range incl. `bytes=0-` is unsatisfiable → 416 `*/0`, while no-Range falls to 200 `Content-Length:0`). A truncated worker write or a one-character clamp regression is invisible until a user seeks mid-song. Karaoke is a kept feature; this is the harden target.

**2. Favorites count mismatch with vanished track (HIGH, daily PWA path).** `JellyfinItemsController.kt:110` returns `ItemsResult(items, favorites.size, startIndex)` — `TotalRecordCount = favorites.size`, but `items` come from `getTracksByIds` (`:107`) which silently drops deleted rows. `ItemsPaginationIntegrationTest:115` seeds favorites and asserts _order_ with `Limit=200` — it never deletes a track row, so it never exercises count≠items. With the scanner ghost-row reality, dangling favorites are likely; the PWA infinite-scroll then requests a page that never fills → stuck Favorites view. No test fires this.

**3. Subsonic clamp-206 + getCoverArt round-trip (MEDIUM).** `MediaStreamController.kt:92` clamps `end=minOf(requestedEnd,fileSize-1)`. The only test asserts the _416_ case (`bytes=99999999-`); the symmetric `bytes=0-99999999`→206-clamped (the actual fix) is unguarded. `getCoverArt`/`download` paths and the `safeRoot` containment guard (`MediaPathSafety.kt:32`) are never exercised for these entry points.

**4. WebSocket per-user routing (MEDIUM, not 🔴 here).** `WebSocketNotificationPublisher` routes via `convertAndSendToUser`; Spring's user-destination handler delivers to _every_ session of a principal (correct for multi-device) and isolates across users — but zero STOMP tests pin either half, and `extractUserId`'s `takeIf{isTextual}` silently broadcasts to `/topic` (everyone) if a payload ever serializes `userId` numerically. PWA is SSE today → down-rate to MEDIUM, contra R1's CRITICAL.

**5. MPD `command_list_ok` / SW autoUpdate / infinite-query offset — LOW.** Convenience/heavy-harness surfaces; real but lowest ROI.

**Ranking:** 1 ≈ 2 (daily PWA + kept feature, cheap Backend-TC) > 3 > 4 > 5.

Sources:

- [RFC 7233 §4.4 — 416 + Content-Range `*/length`](https://www.rfc-editor.org/rfc/rfc7233)
- [Spring — STOMP User Destinations (convertAndSendToUser routing)](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/user-destination.html)

---

## Round 3 — Defense

The pyramid says ROI = (user-impact × regression-likelihood ÷ effort), and you adapt it to _this_ project's risk profile ([Fowler](https://martinfowler.com/articles/practical-test-pyramid.html), [TestRail](https://www.testrail.com/blog/testing-pyramid/)). For a solo/family PWA on Jellyfin+SSE, the prosecution's remaining six gaps split cleanly into "daily-path / silent-corruption" (concede) and "secondary-surface / theoretical" (defer). I concede the ones that bite; I contest the ones that don't.

**CONCEDE — write these:**

- **Karaoke clamp (RFC 7233).** This is _just-changed code on a kept daily feature_ with zero HTTP guard. The failure mode is exactly the one the field reports: a player requesting past EOF gets a 416 or a corrupt 206, "broken media in DevTools" ([MDN](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Range_requests), [RoseHosting](https://www.rosehosting.com/blog/http-416-error-range-not-satisfiable-what-is-it-and-how-to-fix-it/)). Cost: M, extends `AudioStreamIntegrationTest` (temp file + JDBC asset seed, recipe already proven). Risk-of-leaving: silent seek breakage. Write it.
- **infinite-query `maxPages`.** Guards the daily Library/Favorites scroll. But it pairs with the `TotalRecordCount`-counts-vanished bug (already conceded) — together they cause a real "scroll never terminates / dup tracks" defect. Worth a Playwright case despite heaviest harness. Concede, lower of the two.

**DEFER — genuinely low for this deployment:**

- **WebSocket/STOMP isolation + CONNECT reject.** The PWA uses **SSE, not STOMP** — `/user/queue/*` is _dead code in production today_. Per-user privacy leak between A and B requires two STOMP clients that don't exist on the daily path. High harness cost (L, new `WebSocketStompClient` scaffold), zero current users. Defer until/if a STOMP client ships. This is the clearest theoretical-surface dismissal.
- **MPD `list_OK` / Subsonic stream+coverArt.** Secondary protocols (ncmpcpp, Symfonium/Finamp) nobody on this deployment touches daily. The Subsonic range bug's blast radius is third-party clients only; Jellyfin (the daily path) already has the 416 guard. New raw-TCP harness for MPD is pure cost against a non-user. Defer.
- **SW autoUpdate reload.** Fires once per deploy; ChunkLoadError is recoverable on refresh and rare for a single deploying user. Heaviest harness (Playwright) for lowest frequency. Defer.

**Net:** the prosecution's "false confidence" framing over-indexes on mutation-survival as a universal gate. For STOMP/MPD/Subsonic those mutants live in code the user never executes — pinning them is negative-ROI ceremony. The honest frontier is two writes (karaoke clamp, infinite-query), plus the already-fixed auth/idempotency/ghost cluster. Everything else is secondary surface, correctly deferred.

Sources:

- <https://martinfowler.com/articles/practical-test-pyramid.html>
- <https://www.testrail.com/blog/testing-pyramid/>
- <https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Range_requests>
- <https://www.rosehosting.com/blog/http-416-error-range-not-satisfiable-what-is-it-and-how-to-fix-it/>

---

## Pass 3 — Final Verdict

### TL;DR

The robustness remediation was largely sound, but the coverage audit surfaced **three real bugs hiding inside the new code** (not mere test gaps) plus one regression introduced by the favorites change — all now **fixed and pinned by integration tests**. The remaining frontier is small and mostly secondary-surface (STOMP/MPD/Subsonic) that this single-user PWA deployment never exercises daily.

### Bugs found by the audit and FIXED this pass (self-verified ✅)

1. ✅ **Ghost-row backfill was a silent no-op** — `LibraryWriter.backfillNullLibraryRoot` matched an _absolute_ `LIKE '/root%'` prefix against _relative_ `source_path` values (`root.relativize`), so it never adopted any ghost. Replaced with `findTrackIdSourcePathsByLibraryRootOrNull` (candidate set includes `library_root IS NULL`); the present-paths filter deletes the absent ghosts. Pinned by `LibraryWriterHygieneTest.vanished NULL-library_root ghost rows are swept`.
2. ✅ **Deactivation bypass via token cache** — `DeactivateUser` didn't evict the 10s Caffeine cache → a deactivated user kept authenticating. Now publishes `UserSecurityChangedEvent` → `invalidateUser`. Pinned by `RemediationSecurityIntegrationTest.admin deactivation rejects the user's token immediately`.
3. ✅ **Password reset never revoked sessions** — `AuthHandler.changePassword` rewrote only the hash; old bearer tokens lived forever (OWASP ASVS V3 violation). Now revokes all `apiTokens` on change. Pinned by `RemediationSecurityIntegrationTest.admin password reset revokes the user's existing tokens immediately`.
4. ✅ **Favorites count regression** — switching the total from `resolved.size` to `favorites.size` made `TotalRecordCount` count vanished tracks → PWA infinite scroll requests a page that never fills. Now filters favorites by a single `trackIdsExist` existence query; total = resolvable count. Pinned by `FavoritesConsistencyIntegrationTest`.

### Tests written this pass (18 new, all green)

- `RemediationSecurityIntegrationTest` (5): IDOR preferences read+write, getUser self/admin, deactivation eviction, password-reset revocation.
- `ArtistPaginationIntegrationTest` (3): recursive artist page slice + TotalRecordCount, no-dup/skip across offset boundary, past-end empty page.
- `IdempotencyStoreConflictIntegrationTest` (3): ON CONFLICT no-op, DO-NOTHING keeps first record, concurrent stores.
- `KaraokeRangeIntegrationTest` (5): RFC 7233 clamp past-EOF→206, sub-range, suffix, start-past-EOF→416, no-range→200.
- `FavoritesConsistencyIntegrationTest` (1): count matches resolvable items after a track vanishes.
- `LibraryWriterHygieneTest` (1): ghost-row sweep adopts NULL-root, keeps present.

### Remaining gaps — deliberately deferred (low ROI for this deployment)

- **WebSocket per-user STOMP isolation** — PWA uses SSE, `/user/queue/*` is dead in production; heavy STOMP harness. Defer.
- **MPD `command_list_ok` / Subsonic stream-clamp + getCoverArt round-trip** — secondary protocols, non-daily; Jellyfin (daily path) already guarded. Defer (note: a raw-TCP MPD harness would be the only way).
- **SW autoUpdate reload + infinite-query `maxPages` offset** — Playwright-only, once-per-deploy / recoverable-on-refresh. Lowest ROI; worth a future `pwa.spec.ts` + deterministic `page.route` scroll test.

### Verdict

Coverage of the remediation is now real where it matters: every confirmed bug and the daily-path behaviors (auth isolation, artist pagination, idempotency, karaoke seek, favorites integrity) are pinned by integration tests; the unpinned remainder is secondary-protocol surface this deployment doesn't touch.

## Audit Log

- Date: 2026-05-31 (Pass 3 — coverage of the robustness remediation)
- Project: /Users/nikolay/yay-tsa
- Skill: /review-tests
- Findings: 3 real bugs in the new code + 1 regression — all fixed; 18 new integration tests added (all green); ~5 gaps deferred as low-ROI secondary surface
- Agents used: 10 + 5 + 2 + 1 = 18

## Pass 3 — Addendum (deferred gaps closed in follow-up)

Closed the remaining gaps the verdict had deferred, and fixed one more real bug they exposed:

- ✅ **REAL BUG fixed — Subsonic `/rest/stream` returned 500 on any Range with a body.** `MediaStreamController` built a `ResponseEntity<*>` with a `ResourceRegion` body; the erased `Object` type can't be matched by `ResourceRegionHttpMessageConverter` → `HttpMessageNotWritableException` → 500 (the exact trap documented in `JellyfinKaraokeController`). Symfonium/Finamp could not stream at all. Rewrote `/rest/stream` to write bytes directly to `HttpServletResponse` (RFC 7233 clamp included); `/rest/download` + `/rest/getCoverArt` keep the working `Resource` path. Pinned by `AudioStreamIntegrationTest` (clamp-206 + getCoverArt round-trip).
- ✅ **MPD `command_list_ok_begin`** — `MpdProtocolIntegrationTest` connects over TCP (MPD enabled on a free port in the shared base) and asserts a `list_OK` per sub-command + one terminating `OK`, plus the plain `command_list_begin` (no `list_OK`) path.
- ✅ **WebSocket per-user routing (P0-2)** — `StompPerUserRoutingIntegrationTest` (RANDOM_PORT; cache+bucket4j disabled to avoid the singleton-JCache collision) connects two STOMP sessions and asserts a `userId` payload reaches only the owner's `/user/queue/*` while a no-`userId` payload broadcasts to `/topic/*`.
- ✅ **Infinite-query `maxPages` offset** — `apps/web/tests/e2e/pagination.spec.ts` mocks `/Items` deterministically and asserts no duplicate/skipped album ids across the eviction boundary. (Runs under `npm run test:e2e` against the dev-server harness — written + type/format-clean, executes in the e2e environment.)
- ⏸️ **SW autoUpdate reload — NOT feasible in the current harness.** vite-plugin-pwa disables the service worker under `npm run dev` (`devOptions.enabled=false`), which is what Playwright drives; `registerSW`'s `controllerchange→reload` is a no-op there. Testing it needs a production-build/preview webServer the harness doesn't have. Deferred with this concrete blocker.

Test-infra note: enabling a second Spring context (RANDOM_PORT / mpd) collides on the JVM-singleton JCache `CacheManager`. Resolved by running MPD inside the shared MOCK context and, for the STOMP RANDOM_PORT context, disabling `spring.cache.type` + `bucket4j.enabled` (neither is under test there). Added `testImplementation(spring-boot-starter-websocket)` to `app` for the STOMP client.
