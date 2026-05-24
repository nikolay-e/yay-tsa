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
