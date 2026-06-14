# CORRECTNESS — /review-correctness findings log

## 2026-06-14 23:10 · d42363c6 · range 549fe956..d42363c6 (LLM-DJ LiteLLM rework + per-user spend, lease-transfer, Subsonic savePlayQueue, device sync, CI rollout-wait/exclude-paths)

### TL;DR (LLM-DJ + device-transfer run)

Two real product surfaces are broken and one is quietly spending money in prod. **The device-transfer / remote-control feature is dead end-to-end** (the button never renders AND a transfer would play nothing). **The live LLM-DJ has no freshness gate** — every active listening session is billed a GPT-5.4 Mini call every 30 s forever (bounded only by the dedicated key's $5/30 d cap, after which it degrades to ML-only). Round 2 refuted the two scariest scout claims: lease-transfer is **not** a cross-user hole, and getPlayQueue does **not** reorder. The CI exclude-path no-ops were fixed this pass.

### Action list (ranked by impact — every finding has one verdict)

1. **VERIFY** — 🟠 `LlmOrchestrator.kt:59,80,178` + `JpaAdaptiveQueryPort.kt:28,49` — **no "new signal since last decision" gate.** `@Scheduled(fixedDelay=30_000)` sweeps `findByEndedAtIsNull()` (every un-ended session); the only skip is `signals.isEmpty()`; `getSignals(…,20)` returns "latest-20-ever" (no `> lastDecisionAt`); `triggerSignalId` is written but **never read back** (grep: no exists/since query). So each active session pays a full LLM call every tick indefinitely — the startup "LLM-DJ added 5 tracks" burst. **gate:** add a watermark (skip when newest signal ≤ last decision's `triggerSignalId`) + a session-staleness cutoff, then confirm prod `llm.enabled` and the key budget. Live cost — top priority. _(Bounded by the $5/30 d key budget + graceful ML-only fallback, so not 🔴, but it burns the budget fast.)_
2. **DO ✅ FIXED this pass** — 🔴 `.github/workflows/ci.yml:398` schemathesis-exclude-paths had `/v1/groups/{id}/events` (real template is `{groupId}` — `GroupController.kt:23,106`) so it excluded nothing → the SSE endpoint was fuzzed into false conformance failures; and it was **missing** `/Karaoke/{trackId}/status/stream` (`JellyfinKaraokeController.kt:23,86`, a `TEXT_EVENT_STREAM_VALUE` SSE). Fixed: `{id}`→`{groupId}`, added the karaoke SSE, removed the phantom `/v1/sessions/{sessionId}/queue/events` (no such route). QA.md mirrored. schemathesis `--exclude-path` is a literal template match, so the brace name must equal the springdoc param name.
3. **DO** — 🔴 `JellyfinDevicesController.kt:56-61,105-118` (`DeviceSessionDto`) returns 4 fields; TS `DeviceInfo` (`packages/core/src/api/device.types.ts:4-16`) declares 11 **non-optional**; `device.service.ts:6` raw-casts `get<DeviceInfo[]>` (no mapper) → `isOnline` is always `undefined`; `DevicesPanel.tsx:95` gates the whole action block — incl. "Transfer here" (`:142`) — behind `device.isOnline`, so **the transfer button never renders**. The `/events` SSE that would patch state is a keepalive stub (`:63-83`, emits only `ready`). Fix: project real fields (derive `isOnline` from `lastSeenAt`/lease TTL) or make the fields optional. Contained DTO-contract fix.
4. **VERIFY** — 🔴 `apps/web/src/features/player/stores/device-store.ts:64-79` — `transferHere` no longer starts playback. Commit 483bb7aa collapsed the DJ-session and plain-now-playing branches into one `restoreSession()` path and deleted the branch that fetched the track (`ItemsService.getItemsByIds`) and called `playTrack`; `restoreSession()` (`session-store.ts:199-205`) early-returns for a plain transfer, and the trailing `seek` hits an empty engine. Backend returns `currentEntryId` (`JellyfinDevicesController.kt:237`) with **zero FE consumers**. **gate:** a Playwright transfer E2E before claiming the feature works. **#3 + #4 compound — the feature is dead end-to-end, not merely degraded.**
5. **VERIFY** — 🟡 `PlaybackHandler.kt:224-231` `play()` resume drops elapsed time: when `resetPos=false` (Play with `entryId=null`, or targeting the current entry) while already `PLAYING`, it keeps the stale stored `lastKnownPosition` but resets `lastKnownAt=ctx.requestTime`. Per Variant B (`computePosition = lastKnownPosition + (now-lastKnownAt)`), resetting `lastKnownAt` discards the elapsed delta → position jumps backward. **gate:** confirm a redundant Play-while-PLAYING is reachable from a client; fix by recomputing `lastKnownPosition=s.computePosition(...)` in the non-reset branch. Bounded (≤ one segment).
6. **DO** — 🟡 `AdaptiveHandler.kt:144` + `LlmOrchestrator.kt:122,269-285` — "RewriteQueueTail" is **append-only, never trims** (`kept = queue.filter{position<keepFromPosition}`, `keepFromPosition=freshEntries.size` keeps all), and `parseTrackSuggestions` has no `.take(N)` and no `MAX_QUEUE_SIZE` guard (unlike `PlaybackHandler.MAX_QUEUE_SIZE=10_000`). Prompt asks for 5 and `distinctBy` dedups within the batch, so growth is slow, not the scout's "50 appended" explosion — but unbounded over time, with no dedup against the **live** queue (prompt sends only `queue.size`, not IDs). Cap suggestions + dedup against existing trackIds. Deceptive name.
7. **DO** _(judgment)_ — 🟡 `LlmClient.kt:22,62` reads `yaytsa.llm.system-prompt` but that key exists nowhere in `application.yml`/chart → the system-message branch is dead; the DJ steers only via the user-role prompt. Add the key (with a real DJ system prompt) or accept inline-only steering as intentional.
8. **DO** — 🟡 `LlmOrchestrator.kt:174-189` + `LlmClient.kt` `parseCompletion` — spend-audit is hollow: `LlmDecisionEntity.prompt_tokens/completion_tokens` are never populated and the proxy `usage` block is discarded → per-decision cost unreconstructable. Parse `usage`, persist token counts.
9. **VERIFY** — 🟡 `SubsonicController.kt:702-705` getPlayQueue — order **is** preserved (`JpaLibraryQueryPort.kt:254-261` "Preserve caller-supplied order", `mapNotNull` over an id→entity map — the scout's "reorders" claim is **FALSE**), but `mapNotNull` silently **drops deleted ids** → the resumed `entry` list is shorter than the saved `current`/`position` index points into. **gate:** decide whether to reclamp `current` when a track vanished.
10. **DON'T** — `V005__saved_play_queue.sql:4` comment says "jsonb array" but the column is `text[]`. Migration is **already applied in prod** and `FlywayConfig` uses default `validateOnMigrate=true` via `@Bean(initMethod="migrate")`, so editing the comment changes the checksum → startup fails "Migration checksum mismatch". Leave it, or correct via a _new_ migration; never `flyway repair` reflexively. _(⚠ reconciles with the /qa pass earlier today, which made then reverted this exact edit for the same Flyway-checksum reason — consistent, before vs after deploy.)_
11. **DON'T** — lease-transfer is **not** a cross-user authz hole (scout 🔴 refuted): the repo PK is composite `(userId, sessionId)` (`JpaPlaybackSessionRepository.kt:30`) and the controller scopes by `UserId(principal.name)` → another user's sessionId 404s. The `fromDeviceId=currentOwner` shortcut only lets a user steer their **own** session across their own devices. At most a 🔵 (endpoint doesn't verify the caller's own device holds the lease). Also benign/refuted: `from==to` transfer (idempotent refresh), `removeFromQueue` while PAUSED leaving null-current (`play()` recovers via `queue.first()`), and the rollout-wait "3 consecutive" proxy (adequate for its anti-502 purpose).

### Systemic patterns

1. **No freshness/idempotency gate on a paid, scheduled side-effect** — adaptive signals read as "latest-N-ever" not "since last decision", `triggerSignalId` write-only; root cause of the runaway spend and the re-pay-on-failure loop (#1, #6).
2. **TS types lie about the backend contract** — `device.service.ts` raw-casts wire JSON (4 fields) to a rich interface (11 non-optional); compiler happy, runtime `undefined`. A refactor (483bb7aa) then removed the one branch that worked without the stubbed SSE. The "feature looks done" surface (types, endpoints, toasts) hides a dead end-to-end path (#3, #4).
3. **Declared-but-unwired capability** — `system-prompt` key read but never set; `*_tokens` audit columns declared but never filled (#7, #8).

_Scouts/synthesis: 4/2._

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
