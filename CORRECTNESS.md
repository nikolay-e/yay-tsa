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

---

## 2026-06-20 (run 2) — Audit of the fixes themselves (`85222010`) + the PlayerBar refactor (`f512cd48`)

Scope: the only un-audited code since run 1 was the fix commit `85222010` (which applied run 1's prescriptions) and the presentational refactor `f512cd48`. Build + type-check green before this pass, so scouts verified the **fixes' own correctness + regression-freedom + consistency**, not existence. Pyramid 4 scouts → 1 reconcile + adversarial verify of the one defect. Fix applied + type-check/`:app:compileKotlin` green this pass.

### 🟡 1 — `DeviceSseNotificationBridge` null-lease early-return stranded the observer's device card on a stale track (regression introduced by run 1's fix #4)

- Run 1 logged a 🔵 "SSE could emit `deviceId: null`" and "fixed" it (`85222010`) with `val deviceId = nowPlaying.controllingDeviceId ?: return` — dropping the `device_state_changed` emit entirely when no device owns the lease — justified by "the device list refetch on the next heartbeat reflects the stop."
- **That justification is false.** Traced end-to-end: (a) `releaseLease` (`PlaybackHandler.kt:78` → `lease = null`) is the case that nulls `controllingDeviceId`; `stop()` keeps the lease so it still emits a real deviceId — so the null path is exactly the genuine "device stopped owning playback" event, which `PlaybackUseCases` still enqueues to the outbox. (b) The PWA heartbeat (`useDeviceHeartbeat.ts:5`) is **POST-only every 15s and never re-reads the device list** — there is no periodic `fetchDevices`. (c) `device_offline` is emitted by **no** backend code (only group `member_joined/left` + this `device_state_changed`); the PWA's `handleDeviceOffline` listener is dead — no compensating path. (d) Pre-fix, the `deviceId: null` event hit the PWA's `else` branch (`useDeviceEvents.ts:44`, unknown deviceId) → `store.fetchDevices()` → the **only** live refresh that cleared the releasing device's card. The early-return removed it; the observer's card now shows the released device stuck on its last track until the Devices panel is manually reopened.
- **Fix (this pass):** `DeviceSseNotificationBridge.kt` — removed the `?: return`, restored emitting `controllingDeviceId` (nullable). On a release the PWA can't patch (no deviceId matches) and falls through to the full-list refetch — the intended handling of an unmatchable event, not a bug. Made the type honest the _correct_ way: `DeviceStateEvent.deviceId` → optional (`device.types.ts`). `useDeviceEvents.ts` now passes `knownDevice.deviceId` (guaranteed string in the matched branch) so the in-place patch path still type-checks. Comment rewritten to state the real mechanism.
- **Acceptance:** release a lease on device A while device B observes → B's card clears via the full-refetch fallback (not stale); normal play/pause/stop on A still patches B's card in place.

### Confirmed correct-as-written (3 of 4 fixes + the refactor — no change)

- **Outbox publisher isolation (`OutboxEntryProcessor` + `BestEffortNotificationPublisher`)** — exhaustive: the only 3 prod `NotificationPublisher`s are `WebSocketNotificationPublisher` (authoritative, `@Primary`, unmarked, genuinely throws `MessagingException` on send failure so rollback+retry is reachable — not cosmetic), `LoggingNotificationPublisher` (`@ConditionalOnMissingBean`, never instantiated in prod, can't throw), and `DeviceSseNotificationBridge` (correctly the only one marked best-effort). Injection (`List<NotificationPublisher>`), `REQUIRES_NEW`, and `OutboxPoller` retry intact. At-least-once duplicate SSE on retry is idempotent on the client (no-op re-patch). Op-note: no retry cap / dead-letter — pre-existing design property, not a regression.
- **`McpTools.setPreferenceContract` single-read/single-ctx** — `ctxFactory.create(uid, version=INITIAL default)` is one function with a defaulted arg (compiles as both 1- and 2-arg forms); `AggregateVersion.INITIAL` is the correct create-default (handler substitutes `empty()` at version 0, strict-equality OCC); `updatedAt = ctx.requestTime` is the single clock read. **Consistency sweep:** no other `McpTools` write method (`playbackCommand`, `addToQueue`, `clearQueue`, `startRadio`) retains a double-find/double-create — the dedup was unique to the merge-then-write path.
- **`karaokeStem: 'instrumental'` reset** sits in the same track-change `set()` as `isKaraokeMode`/`karaokeStatus`; unreachable for same-track stem switches (those go through `toggleKaraokeStem`→`karaokeSwitchUrl`, never `loadAndPlay`), so it never clobbers an intentional mid-track vocals selection; the gapless path can't run in karaoke mode (preload disabled), so `loadAndPlay` is the only reachable advance and the stale-`vocals` leak is closed on every path. `toggleKaraokeStem` failure-revert is a single correct inverse flip (no double-flip).
- **`KaraokeStemButton` extraction (`f512cd48`)** is byte-for-byte behavior-preserving: `active`/`if (!active) return null` ≡ `isKaraokeMode && (…)`; all aria/title/icon (Music2=vocals, MicVocal=instrumental)/disabled/className/onClick identical; `MicVocal`/`Music2` imports removed from `PlayerBar` with no dangling refs.
- **`useDeviceEvents` `?? undefined`** for now-playing fields is type-honest and rendering-neutral (consumers use truthy checks; no consumer distinguished null from undefined).

### Systemic pattern

- **A "type-honesty" cleanup silently amputated a functional path.** Run 1 read the PWA's full-refetch-on-unmatched-deviceId as wasteful noise and removed the event that triggered it, on a false belief about a heartbeat refetch that doesn't exist. Lesson: before deleting an emit because its payload "matches nothing," confirm what the receiver _does_ with an unmatched payload — here the no-match was the load-bearing refresh signal.

Scouts/synthesis: 4/1 (+ adversarial verify of the one defect, fix applied same pass).

---

## 2026-06-20 — Correctness audit of newly-added features (MCP tools, device WS→SSE bridge, vocals toggle)

Scope: the four feature commits `c0b64969` (MCP tools), `f8458059` (device bridge), `14465468` (vocals toggle). Build + integration tests were green before this audit, so scouts focused on logic, not existence. Pyramid 4 scouts → reconcile. All confirmed findings below were **fixed in the same pass**.

### 🔴 1 — Outbox fan-out swallowed the authoritative WebSocket publisher's failures → permanent notification drop

- `infra-notifications/.../OutboxEntryProcessor.kt` — the device-bridge change rewrote the single `publisher.publish(...)` into `publishers.forEach { runCatching { it.publish(...) } }` then unconditionally `entry.publishedAt = clock.now()`. The prior path let a publish exception propagate → `REQUIRES_NEW` rollback → `OutboxPoller` retry (the manifesto's at-least-once guarantee). The new `runCatching` swallowed **every** publisher's exception, including `WebSocketNotificationPublisher`, marking the entry published → a WebSocket broker failure now drops the notification permanently. (The SSE bridge already swallows its own failures internally, so the only publisher whose exceptions the blanket `runCatching` newly swallowed was the authoritative WebSocket one — pure regression.)
- **Fix**: added marker interface `BestEffortNotificationPublisher`; `OutboxEntryProcessor` isolates only best-effort publishers (`runCatching`) and lets authoritative ones propagate (rollback+retry). `DeviceSseNotificationBridge` now implements the marker.
- **Acceptance**: with the WebSocket publisher throwing, the outbox entry stays `publishedAt = null` and is retried; an SSE-bridge failure does not block the entry.

### 🔴 2 — Karaoke `karaokeStem='vocals'` survived a track change → next track silently auto-enabled vocals-only

- `apps/web/.../player.store.ts` — `karaokeStem` was reset only in `initialState` and `disableKaraokeMode`, not on track change. With karaoke left enabled, advancing the queue ran `syncKaraokeForTrack → applyReadyKaraokeState → karaokeStemUrl(...)`, which read the leftover `'vocals'`, so the new track played vocals-only — contradicting the in-code intent ("each fresh enable starts from the instrumental default").
- **Fix**: reset `karaokeStem: 'instrumental'` in `loadAndPlay`'s state-set (same set that resets `isKaraokeMode`/`karaokeStatus`).
- **Acceptance**: enable karaoke + toggle to vocals on track A, advance to track B → B plays the instrumental stem (default), not vocals.

### 🟡 3 — MCP `set_preference_contract` read the aggregate twice and built two CommandContexts

- `adapter-mcp/.../McpTools.kt` — `preferencesQueries.find(uid)` was called twice (once for the merge `current`, once for the OCC `version`) and `ctxFactory.create(uid)` twice (once for `updatedAt`'s `requestTime`, once for execute). The two reads could straddle a concurrent write (merged fields from an older snapshot than the reported `version` → a narrow lost-update OCC cannot catch), and `updatedAt` came from a throwaway clock read (violates single-requestTime).
- **Fix**: read the aggregate once and build the `CommandContext` once; derive `updatedAt` from `ctx.requestTime`.

### 🔵 4 — Device SSE could emit `deviceId: null` (no lease) + misleading `instrumentalUrl` locals

- Bridge emitted `deviceId = controllingDeviceId` which is null when no device holds the lease (the PWA then matched nothing and full-refetched). **Fix**: skip the live patch when `controllingDeviceId == null` (the heartbeat refetch reflects the stop); this also makes the non-optional `DeviceStateEvent.deviceId` type honest. Added `?? undefined` normalization on the SSE handler for parity with the list path.
- Two `player.store.ts` locals named `instrumentalUrl` actually held whatever stem was active. **Fix**: renamed to `stemUrl`.

### Confirmed correct-as-written (no change)

- MCP `start_radio` matches `JellyfinAdaptiveController.startSession` (INITIAL version is inert — no OCC on session create); capability whitelist exactly matches the issuing tools (no 403 gaps, no orphans); queue-tool lease/deviceId/`QueueEntryId` plumbing correct (errors surfaced, not swallowed).
- Vocals `toggleKaraokeStem` optimistic-set-then-revert lands on the correct previous stem (no double-flip); guards (`isKaraokeMode && !isKaraokeTransitioning` + `controller.ifIdle`) are right; preload is a safe no-op in karaoke mode; the stem stream paths match the backend `/Karaoke/{id}/{instrumental,vocals}` routes byte-for-byte.
- Device contract is camelCase on both sides for the `/v1/*` extension (matches), `isPaused = playbackState != "PLAYING"` derived consistently on list + SSE paths, `DeviceNowPlayingResolver` entry/queue matching + `computePosition` correct, `DeviceEventBroadcaster` CopyOnWriteArrayList iteration + dead-emitter removal concurrency-safe.

Scouts/synthesis: 4/1 (+ fixes applied same pass).
