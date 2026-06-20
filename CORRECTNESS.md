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

## Correctness — Frontend reporter + transport (da63f717)

Scope: `error-reporter.ts` (ErrorReporter), `beacon-error-transport.ts` (BeaconErrorTransport), `redact.ts`, `error-transport.interface.ts`. Read-only audit; no code edited.

### 1. never-throw guarantee — NO BUG

`ErrorReporter.capture` (`error-reporter.ts:31-68`): the only statements outside `try` are `if (this.capturing) return; this.capturing = true;` (`:32-33`) — neither can throw. Every throwing call (`truncate`, `errorName`/`errorMessage`/`errorStack`, `redactSecrets`, `fingerprintOf`→`hash`, `Map.get/set/size`, `reduceUserAgent`, `transport.send`) is inside the `try` (`:34-62`), `catch{}` swallows (`:63-64`), `finally{this.capturing=false}` (`:65-67`) always resets the guard even on throw/early-return. `transport.send` is synchronous and itself wrapped (see #2). `errorMessage` String() is guarded (`:104-108`). `crypto.randomUUID` runs once in the field initializer `newSessionId()` (`:20,90-94`), uses optional chaining + fallback — but note it runs at construction time, NOT inside capture's try (see minor note #7). **No throw escapes capture().**

### 2. self-suppression / reentrancy — NO BUG (firebreak is the transport's .catch, as the prompt suspected)

The `capturing` flag (`:23,32-33,66`) only blocks **synchronous** re-entry. `transport.send` is fire-and-forget; by the time `nav.sendBeacon`/`fetch` settles, `capture()` has already returned and `capturing===false`. So the flag does NOT protect the async case — confirmed. The REAL firebreak for the async path is `beacon-error-transport.ts:27` `.catch(() => {})` on the fetch promise: a rejected keepalive fetch is swallowed and never becomes an `unhandledrejection` (which would re-enter `capture` with category `'promise'`). `sendBeacon` returns a boolean (never rejects), so it can't feed the loop. Verified: there is **no path where the reporter's own failure becomes an uncaught error/rejection**. The `capturing` flag still has value: it prevents a throw _inside_ capture (e.g. a future synchronous transport) from synchronously re-entering. Acceptable as-is.

### 3a. 🟡 dedup — `count` is always 1 on the wire (under-reporting, low severity)

`error-reporter.ts:39-45,55`: first occurrence sends `count: 1` (`:55`); repeats only increment the in-memory `seen` map (`:41`) and `return` (`:42`) — they are **never re-sent**, and the already-sent report's `count` is never updated. So the server sees `count:1` for every distinct fingerprint regardless of true frequency; `ClientErrorReport.count` (`error-transport.interface.ts:18`) is effectively dead data. Not a crash bug, but the field is misleading — a high-frequency error looks identical to a one-off. **Fix (if frequency matters):** periodically flush updated counts, or send count on session end / page hide via a second beacon. Otherwise drop the field to avoid implying it's meaningful.

### 3b. cap off-by-one + Map bound — NO BUG (correct, intentional)

`error-reporter.ts:44`: `if (this.seen.size >= MAX_DISTINCT_PER_SESSION) return;` is checked **before** `this.seen.set(fingerprint, 1)` (`:45`), and only for _new_ fingerprints (existing ones already returned at `:42`). So the map caps at exactly 25 distinct entries (sizes 0..24 pass; at 25 it stops). Existing fingerprints keep incrementing forever (no new map entries), so the map is hard-bounded at 25 — memory is fine. The count integer could theoretically overflow for an absurdly hot error, but JS numbers don't throw on overflow and it never leaves the process. **No correctness bug.**

### 4. fingerprint normalization — 🟡 over-match in `[0-9a-f-]{8,}` (collapses distinct errors, low severity)

`error-reporter.ts:146-155`. The regex chain order is correct (url → id → digits → whitespace), and each `.replace` is total/cannot throw; `hash()` (`:157-163`) is djb2-xor on char codes, no throw, collision risk acceptable for fingerprint bucketing. **The real issue is `/[0-9a-f-]{8,}/gi` (`:150`):** the char class includes `-` and the a–f letters, so it matches any run of ≥8 chars drawn from `{0-9 a-f -}` — which hits ordinary hyphenated/lowercase words. Examples that get collapsed to `<id>`: `"deadbeef"`, `"facebface"`, `"add-edac"` (8 hyphen/hex chars), `"cafe-bed"`-style tokens, and crucially any all-lowercase a–f word ≥8 long. Two genuinely different error messages whose only distinguishing token is such a word would share a fingerprint → **under-reporting (errors silently merged)**, and the 25-distinct cap fills slightly slower (mostly harmless). It does NOT over-fingerprint (no false splitting). **Fix:** anchor to hex-ID shape, e.g. require word boundaries and a digit, or `\b[0-9a-f]{8,}\b` plus a separate UUID pattern `\b[0-9a-f-]{8,}\b` that requires at least one digit — narrowing to real IDs. Low severity: it only mis-buckets, never crashes.

### 5. 🟠 BeaconErrorTransport byte-budget uses char length, not bytes (real gap — beacon can silently drop)

`beacon-error-transport.ts:4,12`: `KEEPALIVE_BYTE_BUDGET = 60*1024`; `if (body.length > KEEPALIVE_BYTE_BUDGET) return;`. `String.length` counts UTF-16 code units, but `navigator.sendBeacon`'s limit and the keepalive quota are measured in **encoded UTF-8 bytes**. A report dominated by multi-byte text (Cyrillic error messages are routine in this project; CJK; emoji) can be ≤ 60K chars yet > 60K (even > 64K) bytes. Path-by-path:

- The pre-guard at `:12` under-counts → a too-large body passes the guard.
- `nav.sendBeacon(...)` (`:16`) then returns `false` because the body exceeds the UA byte limit. `if (queued) return;` (`:17`) is correctly written — **on `false` it falls through to the fetch path** (good, the fallback wiring is correct).
- `globalThis.fetch?.(..., {keepalive:true})` (`:20-26`) is then attempted, but `keepalive` fetch shares the same per-origin keepalive **byte** quota; an oversized body makes the fetch reject with a TypeError. That rejection is caught by `.catch(()=>{})` (`:27`), so it doesn't throw — but **the error report is silently lost** (neither beacon nor fetch delivered it).
  Net: large multi-byte reports are dropped without trace. Not a crash, but a telemetry-correctness gap precisely for the non-ASCII errors this app produces. **Fix:** measure real bytes — `new TextEncoder().encode(body).length` (or a `Blob([body]).size`) — and compare against the byte budget; consider truncating `stack`/`message` to fit rather than dropping. (The upstream truncation caps are in _chars_ too: `MAX_STACK_LENGTH=4096`, `MAX_MESSAGE_LENGTH=1024` at `error-reporter.ts:7-8` — a 4096-char Cyrillic stack is ~8KB, still well under 60K, so the cap alone doesn't prevent the byte overflow once http/audio/ua fields and a worst-case stack combine.)

### 5b. fetch absence & rejection guards — NO BUG

`globalThis.fetch?.(...)` (`:20-21`) optional-chains a missing `fetch` (returns `undefined`); the trailing `.catch` would throw on `undefined`, BUT the whole block is inside the transport's outer `try/catch` (`:10,28-30`), so a missing `fetch` is swallowed harmlessly. When `fetch` exists, `.catch(()=>{})` (`:27`) covers rejection. `sendBeacon` is feature-detected (`:15`). All firebreaks present.

### 6. truncate / reduceUserAgent / matchLabel — NO BUG (one intentional quirk)

- `truncate` (`error-reporter.ts:115-117`): `value.slice(0,max)+'…'` yields max+1 _chars_ (max content chars + one ellipsis). This is the normal "keep max chars then mark truncation" convention, not an off-by-one defect; downstream byte budget (#5) is the only place length matters and it's char-counted there too. Acceptable.
- `reduceUserAgent` (`:141-144`): returns `undefined` only when `ua` is falsy (`:142`), matching the optional `uaReduced?` field (`error-transport.interface.ts:21`). Otherwise always returns a non-empty `"Browser / OS"` string via `matchLabel` (`:134-139`) which always returns at least `'Other'`. No throw. Correct.
- `matchLabel` ordering (`:119-132`): Edge tested before Chrome (Edge UA contains `Chrome/`), correct precedence. iOS-before-Windows etc. fine. No bug.

### Net for this run

- 🟠 #5 byte-vs-char budget — real, drops large non-ASCII telemetry silently. Highest-impact item here.
- 🟡 #3a `count` always 1 — dead/misleading field.
- 🟡 #4 `[0-9a-f-]{8,}` over-match — merges some distinct errors (under-report).
- Areas 1, 2, 3b, 5b, 6: explicitly **no bug** — never-throw, reentrancy firebreak (transport `.catch`), cap/Map bound, fetch guards, and truncate/UA helpers are all correct.

Scouts/synthesis: 1 (small self-contained surface, single coupled file pair) — non-parallel by topology rule 8.

## Correctness — Config + cross-cutting (da63f717)

Scope: bucket4j filter YAML, SecurityConfig permitAll, build.gradle.kts micrometer-core,
CSRF (highest priority), counter naming. No code edited.

### 🟢 #4 CSRF — VERIFIED SAFE (highest-priority check, definitive: NO bug)

`yay-tsa-v2/app/src/main/kotlin/dev/yaytsa/app/security/SecurityConfig.kt:43`

```kotlin
http
    .csrf { it.disable() }
```

CSRF is **disabled globally** on the application security filter chain (`@Order(1)`, the one
that handles `/v1/*`), and also on the management chain (line 33). The new
`POST /v1/client-errors` is therefore NOT subject to CSRF. A real-browser `sendBeacon`
POST without a CSRF token will succeed in production — the feature is **not** dead in prod.

Cross-check: the existing permitAll POST `/Users/AuthenticateByName` (line 64) lives on the
same chain and relies on the same global `csrf.disable()` — identical, proven-working
posture. The MockMvc test passing 204 unauthenticated is consistent with prod, not a
false positive: `HttpIntegrationTestBase` uses `@SpringBootTest` + `@AutoConfigureMockMvc`
(full real context, real `SecurityFilterChain`), and does not apply any CSRF-mutating
test config. Definitive: no CSRF bug.

### 🟢 #2 SecurityConfig permitAll — NO bug

`SecurityConfig.kt:70-71`

```kotlin
.requestMatchers("/v1/client-errors")
.permitAll()
```

- Correct matcher syntax, placed in the chain **before** `anyRequest().authenticated()`
  (line 79-80). Spring evaluates matchers in declaration order; this rule is reached first.
- No broader `/v1/**` authenticated rule exists anywhere in the chain that could shadow it
  (the only `/v1/*` permitAll entries are `/v1/time` and `/v1/client-errors`; everything else
  falls through to `anyRequest().authenticated()`). Not shadowed.
- Reachable unauthenticated → 204, matching the integration test. Correct.

### 🟢 #1 bucket4j filter YAML — NO bug

`yay-tsa-v2/app/src/main/resources/application.yml:180-192`

Structure matches the existing filters exactly:

- `cache-name: rate-limit-buckets` — same shared cache as `jellyfin-login`, `subsonic-rest`,
  `group-join`, `mcp`. Correct (no typo, no separate cache).
- `url: ^/v1/client-errors$` — properly anchored, same regex style as `group-join`
  (`^/v1/groups/join$`). After Traefik strip-prefix (`charts/yay-tsa-v2/values.yaml:168
stripApiPrefix: true`) the backend sees `/v1/client-errors`, NOT `/api/...`, so the regex
  matches the post-strip path. Correct.
- `cache-key: getRemoteAddr()` — same per-IP key used by `group-join` and `mcp`. With
  `X-Forwarded-For` remote-ip handling configured (application.yml:120-123), this resolves to
  the real client IP behind Traefik. Correct.
- `id: client-errors` — unique; no collision with existing ids
  (jellyfin-login/subsonic-rest/group-join/mcp).
- Bandwidth fields (`capacity/time/unit/refill-speed: interval`) all valid and recognized.
- 30 req/min per IP is sane for client-error telemetry (matches `group-join`'s 30/min).

### 🟢 #3 build.gradle.kts micrometer-core — NO bug

`yay-tsa-v2/adapter-jellyfin/build.gradle.kts:40`

```kotlin
implementation("io.micrometer:micrometer-core")
```

- Correct coordinate; version omitted and supplied by the Spring Boot BOM imported at the top
  of this module (`dependencyManagement { imports { mavenBom(spring.boot.bom) } }`). No version
  skew risk.
- No conflict: `micrometer-core` is already on the runtime classpath transitively via
  `app/build.gradle.kts:65 micrometer-registry-prometheus`; declaring it explicitly in the
  adapter module that uses `MeterRegistry` is the correct, BOM-aligned move (compile-time
  dependency where the import lives).
- `MeterRegistry` is injectable: Spring Boot Actuator + the prometheus registry auto-configure
  a `MeterRegistry` bean; constructor injection in `JellyfinClientErrorsController` resolves it.

### 🟢 #5 Counter naming — NO bug

`JellyfinClientErrorsController.kt:39,68 (yaytsa.client.errors)`, `:28,37 (yaytsa.client.errors.dropped)`

- Micrometer→Prometheus mapping is clean: dots→underscores yields
  `yaytsa_client_errors_total` and `yaytsa_client_errors_dropped_total` (counters get the
  `_total` suffix automatically). Valid Prometheus names.
- No clash with existing metric names (only prometheus registry app-side metric is the
  actuator default set; `yaytsa.client.errors*` is a new namespace).
- Tags are bounded before use (`coerceCategory`/`coerceType`/`coerceVersion` clamp to allow-lists
  / regex), so no unbounded-cardinality label explosion. Correct.

**Verdict: no correctness or config bugs found in the audited config/cross-cutting surface of
da63f717. The CSRF concern (#4) — the one that could have killed the feature in prod — is
definitively resolved: CSRF is globally disabled, so `sendBeacon` POST works in production.**

## Correctness — Frontend handlers + wiring (da63f717)

### 🟠 unhandledrejection: `new Error(String(reason))` can throw inside the listener body (unguarded)

- **File**: `apps/web/src/app/infra/install-error-handlers.ts:30-33`
- **Quote**: `win.addEventListener('unhandledrejection', event => { const reason: unknown = event.reason; reportError(reason instanceof Error ? reason : new Error(String(reason)), 'promise'); });`
- **Why**: `String(reason)` runs in the listener body, BEFORE `reportError` → `ErrorReporter.capture`'s try/catch. If a promise rejects with a value whose `String()` conversion throws, the listener itself throws. Two real cases: (1) `Promise.reject(Symbol('x'))` — `String(symbol)` does NOT throw (it returns `"Symbol(x)"`), so symbols are safe; but `` `${symbol}` `` template would throw — `String()` is the safe form, good. (2) `Promise.reject({ toString() { throw new Error('boom'); } })` — `String(obj)` invokes the throwing `toString` and propagates. (3) `Promise.reject(Object.create(null))` — `String()` on a null-prototype object throws `TypeError: Cannot convert object to primitive value`. Both (2) and (3) throw synchronously in the listener, escaping the reporter's guard. The reporter's own `errorMessage()` helper wraps `String()` in try/catch precisely for this — but that protection is bypassed here because the conversion happens before `capture()`. A throwing listener does not crash the app but the rejection goes unreported and an "Uncaught (in promise handler)" error is logged.
- **Fix**: Pass the raw `reason` to `reportError` and let the reporter's guarded `errorMessage()` stringify it: `reportError(reason instanceof Error ? reason : reason, 'promise')` — but `capture` expects `unknown` already, so simply `reportError(reason, 'promise')` (the reporter handles non-Error via `errorMessage`). If an explicit Error wrapper is wanted, wrap the `String()` call in try/catch or use the same guarded helper.

### 🔵 ServiceWorkerContainer `error` listener is dead code (event never fires)

- **File**: `apps/web/src/app/infra/install-error-handlers.ts:37-39`
- **Quote**: `swContainer.addEventListener('error', () => { reportError(new Error('Service worker error'), 'sw', { type: 'ServiceWorkerError' }); });`
- **Why**: Confirmed against MDN — `ServiceWorkerContainer` fires only `controllerchange`, `message`, and `messageerror`. There is NO `error` event on the container (the `error` event lives on the `ServiceWorker` object, e.g. `registration.active.onerror`, not on `navigator.serviceWorker`). This handler will never be invoked → dead code. SW script load/parse failures surface via `registration.update().catch()` (already handled in `main.tsx:38`) or the registration promise, not via a container `error` event. Not harmful, just inert. The `messageerror` handler on line 40 IS correct (that event exists on the container).
- **Fix**: Remove the `'error'` listener (lines 37-39), or if SW-script-error reporting is desired, hook it onto the registration's worker (`registration.installing/waiting/active` → `worker.addEventListener('error', ...)` and `worker.addEventListener('statechange', ...)` for `redundant`).

### ✅ Capture-phase `error` listener branch logic — correct

- **File**: `apps/web/src/app/infra/install-error-handlers.ts:9-26`
- (a) A normal JS runtime error sets `event.target === window`, so `target !== win` is false → does NOT enter the resource branch → falls through to the runtime branch (line 23-25). Correct.
- (d) `isOpaqueScriptError`: `!event.error && event.message === 'Script error.' && !event.filename`. A real same-origin error virtually always has `event.error` populated (the Error object) and/or a `event.filename`; the only way all three conditions hold is the canonical cross-origin opaque-script case. Misclassification of a real same-origin error is implausible. Correct.

### 🟡 `<audio>` resource error is double-reported (capture listener + engine.onError) with distinct fingerprints — not deduped

- **File**: `apps/web/src/app/infra/install-error-handlers.ts:11-18` AND `apps/web/src/features/player/stores/player.store.ts:855-867`
- **Why**: When an `<audio>` element fails to load, the HTML5 `error` event fires on the element. It does NOT bubble, but this listener is registered with `capture=true` (line 27), so it DOES catch element resource errors → reports as category `'resource'`, type `'ResourceError'`, message `Resource load failed: AUDIO <url>`. Separately, the html5-audio engine's own error path fires `engine.onError` → reports category `'audio'`, type `'AudioError'` with `mediaError`/`readyState`/`networkState`. The reporter's `fingerprintOf` keys on `category|type|hash(message)` — the two reports have different categories AND types, so they produce different fingerprints and are NOT deduped. One audio failure → two distinct client-error reports. This inflates telemetry counts but each is independently meaningful (resource vs media-error-code view). Whether this is a "bug" depends on intent; flagging because it doubles the per-session distinct-fingerprint budget (`MAX_DISTINCT_PER_SESSION = 25`) consumption for audio incidents.
- **Fix (optional)**: If single-report is desired, suppress the capture-phase resource branch for media elements (`AUDIO`/`VIDEO` tags) since the engine reports those with richer context: `if (el.tagName !== 'AUDIO' && el.tagName !== 'VIDEO') { ...report... }`. Otherwise document the dual-report as intentional.

### ✅ api_key in `<audio src>` resource URL gets redacted — correct

- **File**: `redact.ts:3` + `error-reporter.ts:36` + `beacon-error-transport.ts:11`
- The resource message embeds the raw stream URL (`...stream?api_key=SECRET`). `redactSecrets` runs the regex `api_key=[^&\s"')<>]+` over the message in `capture()` (line 36) AND again over the full JSON body in the transport (line 11). At end-of-string the api_key value has no `&` terminator, so `[^&\s"')<>]+` correctly matches the whole secret to EOL → `api_key=[REDACTED]`. Double-redaction (message + body) is defense-in-depth. Correct.

### 🟡 RouteTracker `toRouteTemplate` substring replacement — real but low-severity for this app

- **File**: `apps/web/src/app/infra/RouteTracker.tsx:16-24`
- **Quote**: `template = template.split(value).join(`:${key}`);`
- **Why**: `String.split(value)` is plain-string (no regex), so special-char values are safe (b is a non-issue). But it is a global, unanchored substring replace: ALL occurrences of the param value anywhere in the path are replaced, not just the matched segment. (a) Real risk: a short numeric id like `/albums/1` with `id="1"` → `split("1")` replaces every `"1"` in the path; if the static path contained a `1` it would corrupt. For this app the static segments are `albums/artists/audiobooks/...` (no digits) and ids are Jellyfin 32-hex GUIDs or stable numerics, so collisions are improbable but not impossible. (c) Order dependence: if two params share a value, the first replaces both. (d) URL-encoded path vs decoded `useParams` value: `location.pathname` is raw (may be percent-encoded) while `params` values are decoded by react-router; for an id containing a space/unicode the decoded value won't be found as a substring of the encoded pathname → no replacement, route stays un-templated (raw id leaks into telemetry `route`, defeating cardinality reduction). For GUID/numeric ids this never triggers.
- **Severity**: Low for the current route set (all single-param routes with GUID/numeric ids, digit-free static segments). Would become a correctness problem if a route gained a short/colliding param or a param with encodable chars.
- **Fix**: Build the template from react-router's matched route pattern instead of string-replacing the pathname — e.g. use the `matches` from `useMatches()`/the route handle, or replace only the trailing/segment-anchored occurrence: split pathname on `/`, replace whole segments that equal a param value.

### ✅ player.store engine.onError insertion — control flow preserved, null-safe

- **File**: `apps/web/src/features/player/stores/player.store.ts:855-867`
- `reportError(error, 'audio', {...})` is inserted after `log.player.error` and BEFORE the existing recovery logic (`recoverable`/`controller.interrupt`). It is fire-and-forget (returns void, no await) and reads only local `mediaErrorCode` and `engine.getAudioElement?.()`. It does not alter `recoverable`, the early-return, or recovery timing. `reportError` delegates to `instance?.capture` (optional chaining) → if an audio error fires during very early boot before `initErrorReporter` ran, `instance` is null and the call is a silent no-op (no throw). `getAudioElement?.()` is verified present on the engine interface (`audio.interface.ts:129`, optional) and implemented (`html5-audio.ts:776`) returning `HTMLAudioElement | null`. In practice `initErrorReporter` runs at `main.tsx:20` before any player/engine construction, so the reporter is always initialized by the time audio errors can fire. Correct.

### ✅ main.tsx bootstrap order — correct

- **File**: `apps/web/src/main.tsx:17-21, 43`
- `initErrorReporter(new BeaconErrorTransport('/api/v1/client-errors'))` then `installErrorHandlers()` run BEFORE `createRoot(...).render(...)` and before `registerSW`. This is the right order: handlers installed before the React tree mounts so early render/runtime/resource errors are captured, and the reporter instance exists before any handler can fire. `BeaconErrorTransport` constructor only stores the endpoint string (no window/navigator access at construction) — safe even in a no-window context; actual `navigator.sendBeacon`/`fetch` access is deferred to `send()` and guarded. Endpoint `/api/v1/client-errors`: the v2 backend is served under `/api` on `yay-tsa.com` with a Traefik strip-prefix middleware, so the browser-relative `/api/v1/client-errors` is correct (strip-prefix removes `/api` so the backend sees `/v1/client-errors`, matching the Yaytsa `/v1/*` extension namespace). Correct (assuming the backend route `/v1/client-errors` exists — not in audit scope here).

## Correctness — Backend controller (da63f717)

**File**: `yay-tsa-v2/adapter-jellyfin/src/main/kotlin/dev/yaytsa/adapterjellyfin/JellyfinClientErrorsController.kt`
**Scope**: unauthenticated (`permitAll`) client-error telemetry ingestion. Findings empirically verified with JDK 21 (Kotlin `Regex` wraps `java.util.regex` identically) + jackson-databind 2.18.3.

### 🟡 HIGH_ENTROPY over-redaction destroys legitimate routes/stacks/messages — `:172`, used `:134`

```kotlin
private val HIGH_ENTROPY = Regex("\\b[A-Za-z0-9+/=_-]{32,}\\b")
```

The class includes `/`, `_`, `-`, `+`, `=` — all common in URL paths and stack frames. Verified: `"GET /api/v1/some/very/long/route/path/that/is/normal/text"` → `"GET /[REDACTED]"`; a 32+ char route/file path with no spaces is fully redacted. This silently eats the most useful diagnostic fields (`route`, `stack`, `msg`) for exactly the long, structured errors you most want to read. Why-wrong: `\b` followed by a char class containing path separators makes any long path-like token "high entropy". Fix: drop `/` `-` `_` from the entropy class (keep base64/hex shape `[A-Za-z0-9+/=]{32,}` requires a real charset-balance check) or, better, gate on a Shannon-entropy threshold rather than mere length, and/or never run HIGH_ENTROPY against `route`/`stack` (only against `msg`/headers). At minimum exclude `/` so paths survive.

### 🔵 HIGH_ENTROPY leaks base64 `=`/`==` padding (cosmetic, body still redacted) — `:172`

Verified: `"...Zo=="` → `"[REDACTED]=="`; `"...Zo="` → `"[REDACTED]="`. Because `=` is a non-word char, `\b` sits _before_ the trailing padding, so the `=`/`==` is left in the log. The high-entropy body itself IS redacted, so this is not a secret leak — only stray `=` chars remain. Note also a pure-symbol run (`"+"*32` or `"="*16000`) is NOT matched at all (no `\b` on either side) — verified — but such strings carry no secret. Fix (optional): use lookarounds instead of `\b`, e.g. `(?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/=_-]{32,}(?![A-Za-z0-9+/=_-])`, so padding is captured.

### 🔵 `user`/`sid`/`fp` log-injection vector is closed only by defense-in-depth, `user` is unsanitized — `:61`, `:94`

```kotlin
"user" to principal?.name,   // NOT passed through sanitize()
```

`principal.name` is the only field that skips `sanitize()`. In practice safe because (a) `JellyfinAuthentication.getName()` returns `userId.value` (server-issued UUID, not free text — verified in `JellyfinAuthFilter.kt:103`), and (b) `objectMapper.writeValueAsString` escapes `\n`/`\r`/control chars inside string values by default (verified: `"abc\ndef\r"` → `"abc\\ndef\\r"`), so the log stays single-line regardless. No actual injection. Fix (hygiene/consistency): wrap with `sanitize(principal?.name)` so every logged value goes through the same path; cheap and removes the reasoning dependency on the UUID invariant.

### ✅ `readBounded` loop — correct (no off-by-one, no oversize misclassification)

```kotlin
val buffer = ByteArray(MAX_BODY + 1); var total = 0
while (total <= MAX_BODY) { val read = input.read(buffer, total, buffer.size - total); if (read == -1) break; total += read }
if (total > MAX_BODY) return null
```

Verified end-to-end with a 1-byte-per-call stream:

- Empty body → `""` (NOT null) → flows to JSON parse → throws → **malformed** branch. Intended: empty is malformed, not oversize. Correct.
- Body of **exactly MAX_BODY (16384)** → accepted, `len=16384`. Correct (not wrongly rejected).
- Body of MAX_BODY+1 and MAX_BODY+2 → `null` (oversize). Correct discrimination.
- `buffer.size - total` is always ≥1 while the loop runs: max `total` at loop entry is `MAX_BODY`, giving `len = (MAX_BODY+1) - MAX_BODY = 1`. So a conformant stream never gets a 0-length request and `read()` never returns 0 for `len≥1`. No infinite loop reachable via the servlet stack.
- Note: this reads at most MAX_BODY+1 bytes; if the actual body is larger the stream is NOT fully drained (closed via `.use`). Returning 204 + closing the stream on oversize is fine for a fire-and-forget beacon endpoint.

### ⚠️ (informational, not a bug) `readBounded` infinite loop only under a non-conformant stream

A stream that returns `0` from `read(b,off,len)` with `len≥1` would spin forever (verified with a deliberately-misbehaving stream: runaway at `total=0`). This violates the `InputStream` contract (0 is only legal when `len==0`), and Tomcat/Coyote servlet input streams are blocking + conformant, so it is not reachable in production. If you want belt-and-suspenders, add `if (read == 0) break` or cap iterations.

### ✅ Secret-redaction group refs (`$1`) — correct

Verified all four `SECRET_PATTERNS`:

- `"$1=[REDACTED]"` correctly substitutes group 1: `api_key=SECRET123abc` → `api_key=[REDACTED]`; `?api_key=abc&x=1` → `?api_key=[REDACTED]&x=1` (the `&x=1` tail survives because the value class excludes `&`).
- `Bearer ...` → `Bearer [REDACTED]` (no group; literal replacement — fine).
- `X-Emby-Token: "abc"` → `X-Emby-Token: [REDACTED]`; `token: rawvalue` → `token: [REDACTED]`.
- `{"token":"abc","a":1}` → `{"token":"[REDACTED]","a":1}`.
  Kotlin/JVM treats `$1` as group ref; the literal `$` only matters when it appears outside a valid group ref — none of these replacements have a bare `$`. The `\$`-escaping caveat does not apply here. No `IndexOutOfBounds` (every `$1` has a group 1 in its pattern). Correct.

### ✅ No ReDoS

Stress-tested every regex against 16 KB adversarial inputs (long value runs, huge `\s*` whitespace runs, `("a=").repeat(4000)`, all-`=`, whitespace-then-entropy). No pattern exceeded 50 ms; all are linear (no nested/overlapping quantifiers). Correct.

### ✅ Counter cardinality — bounded on every path

Happy path tags (`:67-68`) are `catTag`∈ALLOWED*CATEGORIES∪{"other"}, `typeTag`∈ALLOWED_TYPES∪{"other"}, `verTag` regex-gated `^[A-Za-z0-9.*-]{1,32}$`else`"unknown"`. Malformed path (`:39`) uses hardcoded `other`/`other`/`unknown`. `coerceVersion`runs`VERSION_PATTERN` on the already-`stringField`-truncated (≤32) value before it can become a tag — no pre-coercion leak. Raw `message`/`type`/`status`/`route`/`ua` never reach a tag; they appear only in the (unbounded-cardinality-safe) log line. No unbounded value reaches Micrometer. Correct.

### ✅ Malformed / oversize / empty — all return 204, nothing throws

- Oversize → 204 (`:29`); malformed parse → 204 (`:41`); happy → 204 (`:70`).
- `stringField` does `value as? String` → a JSON number/object/array for `message`/`type`/etc. yields `null` (no `ClassCastException`). Verified by inspection.
- `intField` on a `Number` calls Kotlin `toInt()` (→ `intValue()`); verified it never throws and saturates/truncates for `Long`/`BigInteger`/huge `Double` (`9999999999`→`1410065407`, `1e308`→`2147483647`). A string like `"9999999999999999999"` → `toIntOrNull()` → `null`. No throw path.
- `objectMapper.writeValueAsString(line)` on a `LinkedHashMap<String,Any?>` of strings/ints cannot throw here.

### ✅ `fullType` TYPE_LOG_LIMIT(100) vs `coerceType` CATEGORY_LIMIT(64) — intentional, not a bug

`:44` keeps a richer 100-char `type` for the _log_ field; `:46` truncates to 64 only to feed `coerceType`, which then matches against a fixed allow-set whose longest member is 17 chars — so the 64 vs 100 difference is immaterial to the tag. Two reads of `parsed["type"]` with different limits is deliberate (full-ish in log, coerced in tag). No correctness impact.

### ✅ Single-line log guarantee — holds

Each string field is `sanitize`d (CONTROL_CHARS incl. ` `/` ` → space), AND Jackson escapes any residual `\n`/`\r` in values (verified). `writeValueAsString` of the map introduces no literal newlines (no pretty-printer configured). Log is single-line.

### Summary

- 🔴 none.
- 🟡 one real bug: HIGH_ENTROPY over-redacts long route/stack/path tokens (`:172`/`:134`), degrading the telemetry it exists to capture.
- 🔵 two minor: base64 `=`/`==` padding leak (cosmetic), unsanitized `user` field (closed by Jackson escaping + UUID principal — hygiene only).
- ⚠️ one informational: theoretical infinite loop only under a contract-violating InputStream (not reachable via servlet stack).
- Everything else audited (buffer loop boundaries, `$1` group refs, ReDoS, counter cardinality, malformed/oversize/empty handling, int/string coercion, fullType limit mismatch, single-line log) is correct.

## Resolution (correctness fixes applied, follow-up to da63f717)

All actionable findings fixed and re-verified (backend 6/6 integration tests, frontend build+eslint green):

- 🟡 HIGH*ENTROPY over-redaction → class narrowed to `[A-Za-z0-9*-]{32,}`(dropped`/ + =` so slash-separated routes/URLs are no longer eaten; hex + base64url tokens still caught).
- 🟠 Beacon byte-budget → measure `TextEncoder().encode(body).length` (UTF-8 bytes) instead of `String.length` (UTF-16 units) — correct for non-ASCII/Russian payloads.
- 🟠 `unhandledrejection` unguarded `String(reason)` → pass raw `reason` to `reportError`; stringification now happens inside the reporter's guarded `errorMessage()`.
- 🟡 Double-report of `<audio>`/`<video>` errors → capture-phase resource branch skips media elements; engine `onError` is the single richer source.
- 🟡 RouteTracker substring replace → segment-wise replacement (only whole path segments equal to a param value become `:key`).
- 🔵 Dead `ServiceWorkerContainer` `error` listener removed (container never fires it; `messageerror` retained). Full SW-script-error capture remains deferred (needs SW-side postMessage bridge).
- 🔵 Misleading `count: 1` removed from the wire report; dedup store simplified `Map<string,number>`→`Set<string>`.
- 🔵 Backend `user` field now run through `sanitize()` for consistency.
- defensive: `readBounded` breaks on `read <= 0` (InputStream-contract guard against a 0-return infinite loop).

CSRF verified safe (globally disabled on the `@Order(1)` chain) — endpoint reachable unauthenticated in prod. No 🔴 issues found.

## Correctness audit (14cde4d5) — Scout C: SSE remote-commands/transfer

Range `8ab416a0..HEAD` (commits f8458059 bridge, 483bb7aa lease-transfer/SSE, 14cde4d5 HEAD). WebSocket/STOMP fully removed; all delivery now rides per-user SSE on `/v1/me/devices/events`.

### 🔴 TRANSFER-404 GOAL NOT MET — "transfer here" still 404s for web devices (task drift)

`JellyfinDevicesController.transferLease` (adapter-jellyfin/.../JellyfinDevicesController.kt:201-256):

```kotlin
val current =
    playbackUseCases.getPlaybackState(uid, sid)
        ?: return problemDetail(HttpStatus.NOT_FOUND, "Not Found", "Session not found")
val currentOwner =
    current.lease?.owner
        ?: return problemDetail(HttpStatus.CONFLICT, "Conflict", "No active lease on session")
```

`getPlaybackState` = `sessionRepo.find(userId, sessionId)` (PlaybackUseCases.kt:79-82) — returns null unless a `PlaybackSessionAggregate` was persisted. An aggregate is persisted ONLY inside `PlaybackUseCases.execute` on a successful command (PlaybackUseCases.kt:62-72: `empty(...)` is created in-memory, but only `sessionRepo.save` on Success persists it). The only commands that can succeed on an empty session are `AcquireLease`/`StartPlaybackWithTracks` (PlaybackUseCases.kt:60-61 comment).

Across the whole adapter layer (`grep AcquireLease( / StartPlaybackWithTracks(` non-test) the ONLY `playbackUseCases.execute` calls are:

- `JellyfinDevicesController.kt:170` (`/command`) — itself gated on a pre-existing aggregate + lease.
- `JellyfinDevicesController.kt:235` (`/transfer`) — itself gated on a pre-existing aggregate + lease.
  `AcquireLease(` non-test appears ONLY in `core-domain/playback/Commands.kt` (definition) and `adapter-mpd/MpdCommandHandler.kt`. **No Jellyfin/web endpoint ever issues AcquireLease or StartPlaybackWithTracks.**

The web client confirms this: `packages/core` has NO `AcquireLease`/`StartPlaybackWithTracks`/`startPlayback`/lease call anywhere (grep clean). Web playback is local HTML5 audio + reporting via `/Sessions/Playing[/Progress|/Stopped]`, and `reportPlaying`/`reportProgress`/`reportStopped` (JellyfinSessionsController.kt:130-176) only record signals + resume positions — they do NOT touch the playback aggregate. A web device appears in the device list purely via `/v1/me/devices/heartbeat` → `DeviceSessionProjection.register` (an in-memory presence map, NO aggregate).

**Result:** `transferHere(device.sessionId)` (web `device-store.ts:68` → `device.service.ts:57` POST `/v1/me/devices/{sourceSessionId}/transfer`) targets a session that has no `PlaybackSessionAggregate` → `getPlaybackState` null → **404 "Session not found"**, exactly the prior root cause. The same applies to `sendCommand` (PAUSE/PLAY/etc. from `DevicesPanel`) which 404s identically (JellyfinDevicesController.kt:141-143). The migration built the entire SSE command-delivery pipe DOWNSTREAM of a gate that web traffic can never pass. The new `remoteCommandPort.publish(...)` calls at :172 and :239 are unreachable for web-originated transfers.

**Why wrong:** the 404 was moved, not fixed. The plumbing (RemoteCommandPort → outbox → RemoteCommandSseBridge → SSE `command` event → client `handleSoloCommand`) is correct and would work — but only for an MPD-controlled session that actually holds a lease. Two-web-device control/transfer (the headline use case) remains broken.

**Fix:** the web's controlling device must create/own a `PlaybackSessionAggregate` with a lease. Either (a) have the PWA acquire a lease on first local play (new endpoint or extend `/Sessions/Playing` to issue `AcquireLease` for the reporting device, keyed by its deviceId as sessionId), so the device list reflects real lease-holding sessions; or (b) make `transfer`/`command` lazily materialize an aggregate (`empty(...)` + implicit AcquireLease to `toDeviceId`) when none exists, instead of 404. Without one of these, the transfer feature cannot function for the PWA.

### 🟡 Per-user SSE routing is sound in code, but the test that PROVED isolation was deleted with no replacement

`DeviceEventBroadcaster` (adapter-jellyfin/.../DeviceEventBroadcaster.kt:20-49) keys `subscribers` by `userId` and `emit(userId, ...)` iterates only `subscribers[userId]`. `commands()`/`events()` both `subscribe(principal.name)` (the authenticated user). `RemoteCommandSseBridge` extracts `userId` from the outbox payload and emits to that user only. So a user CANNOT receive another user's commands/events — the security property holds structurally. **No cross-user leak.**

However, the deleted `StompPerUserRoutingIntegrationTest.kt` asserted exactly this property end-to-end (`a user-scoped notification reaches only that user, not another`) and there is NO replacement (`grep -l transferLease|SseEmitter|device-command|RemoteCommand` over test sources = empty; no SSE/transfer integration test exists at all). The isolation invariant is now unguarded — a future refactor that switches `subscribers` to a flat list or mis-keys `emit` would ship undetected. Add an integration test that opens two users' EventSource streams, publishes a `device-command`/`playback` notification for user A, and asserts B receives nothing.

### 🟡 All publishers are now best-effort → remote commands are effectively at-MOST-once (silently dropped if target SSE is momentarily down)

`OutboxEntryProcessor.publishAndMark` (infra-notifications/.../OutboxEntryProcessor.kt:23-31): every remaining publisher (`LoggingNotificationPublisher`, `RemoteCommandSseBridge`, `DeviceSseNotificationBridge`) implements `BestEffortNotificationPublisher`, so each is wrapped in `runCatching` and the entry is ALWAYS marked `publishedAt` after one pass (no authoritative publisher remains after the WebSocket deletion). The poller therefore never retries.

Consequence: `RemoteCommandSseBridge.publish` reads the live `DeviceEventBroadcaster` fan-out; if the target device's EventSource is briefly disconnected (reconnect backoff is up to 30s — useRemoteCommands.ts:128) when the outbox tick fires, the `command` is dropped permanently. For derived STATE (`device_state_changed`) this is mitigated — the client refetches the device list on every reconnect (useDeviceEvents.ts:96-101). For COMMANDS there is no such recovery: a remote PAUSE/SEEK issued during a reconnect window is lost with no client-side reconciliation. This is acceptable for best-effort presence but is a real correctness gap for a control channel that the UI presents as authoritative. Mitigation: either keep a short-lived per-device pending-command record the device pulls on (re)connect, or accept and document it. (Note: because all publishers are best-effort, there is NO duplicate-delivery risk — the originally-feared exactly-once concern is moot.)

### 🔵 Orphan `/commands` SSE endpoint — dead code, harmless

`JellyfinDevicesController.commands()` (:80-87) is identical to `events()` (subscribes the same user to the same broadcaster). The client connects ONLY to `/v1/me/devices/events` (useRemoteCommands.ts:22, useDeviceEvents.ts:23) and listens for the `command` event there. `/commands` is never opened by any client (grep). Both command and state events flow over the single `/events` stream. The duplicate endpoint is dead but harmless; remove it to avoid implying two channels exist.

### 🔵 No server-side SSE keep-alive; dead emitters pruned only lazily

`DeviceEventBroadcaster` has no scheduled ping. A dead emitter is removed only on the next `emit` that throws (:44-47) or at the 30-min timeout (:52). Bounded and self-healing via client reconnect; minor. (Same shape as the existing `GroupEventBroadcaster`, so consistent with the codebase.)

### Client hooks — no regression found

`useRemoteCommands.ts` and `useDeviceEvents.ts`: the consecutive-failure/`degradedReported` counters reset correctly on `onopen` (:131-135 / :90-93) and increment per `onerror`; reconnect backoff `min(2000*attempts, 30000)` is intact; cleanup closes the EventSource and clears the timer. Wire shape matches: bridge emits `{targetDeviceId, type, payload}`; client `JSON.parse` → `RemoteCommand` with `targetDeviceId`/`type`/`payload` (device.types.ts:46-52); target-device filter `cmd.targetDeviceId !== ownDeviceId` (:95) is correct. STOP handling (halts local audio on transfer-away, :60-64) is correct IF a STOP ever arrives — which, per the 🔴 above, it will not for web-to-web transfers.

**Severity counts (Scout C): 🔴 1 · 🟡 2 · 🔵 2.** TRANSFER-404 GOAL: **NOT MET** — the 404 is structurally guaranteed for PWA devices because no web/Jellyfin path ever creates the `PlaybackSessionAggregate` that `transfer`/`command` require; the SSE delivery pipeline is correct but sits behind an unreachable gate.

## Correctness audit (14cde4d5) — Scout A: artwork backend

Range `8ab416a0..HEAD`, artwork area only. Build + tests already green; these are logic bugs the compiler/tests miss.

### 🟡 Image-driven backfill re-hammers external APIs forever for permanently-coverless entities

`infra-metadata-enricher/.../MetadataEnricher.kt` `enrichAlbums()` / `enrichArtists()` / `enrichAudiobookTrackCovers()`.

```kotlin
val pending =
    (albumRepo.findByMetadataCheckedAtIsNull(batchSize, 0) + albumRepo.findWithoutPrimaryImage(batchSize, 0))
        .distinctBy { it.entityId }
```

`findWithoutPrimaryImage` selects rows with NO primary image and is **not gated by `metadata_checked_at`** (by design — "self-healing"). But there is **no negative-result memo and no success requirement**: an album/artist/audiobook track that legitimately has no obtainable cover (no MusicBrainz release-group match, or CAA/OpenLibrary 404) writes no image row, so it reappears in `findWithoutPrimaryImage` on the _next_ poll cycle (default 5 min, `poll-interval-ms:300000`). Each cycle re-runs a full `musicBrainz.searchReleaseGroups(...)` / `searchArtists(...)` / CAA fetch for the same permanently-failing entities — indefinitely. `enrichAlbum` does stamp `metadata_checked_at = now()`, but that stamp is irrelevant to the image-driven query, so it does not break the loop.
Why it's wrong: unbounded external-API traffic to MusicBrainz/CAA for entities that can never resolve; violates "fetch once, then leave it alone" etiquette even though the global 1 req/s `RateLimiter` and `batchSize` cap throughput. On a large art-less library this is continuous background hammering.
Fix: record a per-entity _artwork_ attempt timestamp (separate from `metadata_checked_at`) and exclude entities attempted within a backoff window (e.g. `image_checked_at > now() - 7d`), or add an `image_attempts`/`image_checked_at` column to the WHERE of `findWithoutPrimaryImage`. Success (image row written) naturally drops them from the set; failures need their own cooldown.

### 🟡 Backfill starves entities beyond the first batch (OFFSET pinned to 0)

`AlbumRepository.findWithoutPrimaryImage` / `ArtistRepository.findWithoutPrimaryImage` / `EntityGenreRepository.findAudiobookTrackIdsWithoutPrimaryImage`, called as `findWithoutPrimaryImage(batchSize, 0)` every cycle.

```kotlin
"... ORDER BY a.entity_id LIMIT :limit OFFSET :offset"   // always offset = 0
```

Combined with the no-memo issue above: each cycle the query returns the _same_ first `batchSize` art-less rows (deterministic `ORDER BY entity_id`, `OFFSET 0`). If those first N can never get a cover, art-less entities ranked after them by UUID are **never reached** — the cursor never advances. The one-shot `findByMetadataCheckedAtIsNull` path doesn't help because it stamps and drops out, but the image-driven path is stuck on the head of the list.
Why it's wrong: a wrong "backfill set" in practice — only a prefix of art-less entities is ever attempted once the prefix is saturated with permanent failures.
Fix: same as above — a cooldown column makes resolved/cooled entities fall out of the result so the window advances. Without that, OFFSET 0 + permanent failures = permanent starvation of the tail.

### 🔵 `.img` cover-cache files served with assumed `image/jpeg` content-type

`EmbeddedCoverExtractor.kt` writes `"$key.img"`; `ThumbnailService.contentTypeForFile` maps unknown extensions (incl. `.img`) to `image/jpeg`:

```kotlin
private fun contentTypeForFile(path: Path): String =
    when (path.toString().substringAfterLast('.').lowercase()) {
        "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg"
    }
```

Embedded tag artwork can be PNG. When the client requests the image with **no** `maxWidth/maxHeight` (original passthrough), the bytes are served verbatim but labelled `image/jpeg`. Browsers sniff `<img>` content so it renders, but the declared Content-Type is wrong (matters for strict consumers / `fetch().blob().type`). With a size param `ImageIO.read` transparently decodes and re-encodes to real JPEG, so the mislabel only bites on raw passthrough. Low impact.
Fix: store the artwork's real extension (jaudiotagger `Artwork.mimeType` → ext) instead of a generic `.img`, or sniff magic bytes in `contentTypeForFile`.

### 🔵 Delete-then-insert of a same-entity Primary row risks the partial-unique-index under Hibernate flush ordering

New `ensureAudiobookTrackCover` (scanner) and the unchanged `ensureAlbumCover` both do, when a stale row exists whose file is gone:

```kotlin
if (existing != null) imageRepo.delete(existing)
imageRepo.save(ImageJpa(... entityId = trackId, isPrimary = true ...))
```

`V006__images_primary_unique.sql` adds `CREATE UNIQUE INDEX idx_images_one_primary ON images(entity_id) WHERE is_primary`. Hibernate's default action-queue order within a flush is INSERT-before-DELETE, so within the one `@Transactional upsertTrack` the new row can hit the partial unique index before the old row is deleted → constraint violation at flush/commit. Narrow trigger (only when a prior Primary row points at a now-missing file). Pre-existing pattern (`ensureAlbumCover`, `downloadCoverIfMissing` guard against it by existing-checks), but `ensureAudiobookTrackCover` newly extends the delete-then-insert branch to track entities.
Fix: `imageRepo.flush()` (or `saveAndFlush` on the delete) between delete and save, or update the existing row's `path`/`size` in place instead of delete+insert.

### Verified correct (no finding)

- External-API claims confirmed via Open Library docs: `?default=false` returns a real 404 (not a blank placeholder), and the **CoverID** path (`/b/id/{id}-L.jpg`) is **not** rate-limited (only ISBN/OCLC/LCCN/OLID are, 100/IP/5min). `OpenLibraryCoverClient` correctly uses `/b/id/` and `default=false`. Comment is accurate.
- MusicBrainz 1 req/s etiquette preserved: `OpenLibraryCoverClient` shares the enricher's single global `RateLimiter` (`rateLimiter.acquire()` before both search.json and cover fetch).
- Flag gating is default-OFF and consistent end to end: `openlibrary-enabled:false` (application.yml), `METADATA_OPENLIBRARY_ENABLED: {{ default false ... }}` (configmap), `openLibraryEnabled:false` (values.yaml). `enrichAudiobookTrackCover` only calls Open Library inside `if (openLibraryEnabled)`.
- Title normalization regexes are well-behaved — no over/under-stripping on adversarial titles ("Volume Control", "Book Lovers", "A Novel Idea" survive; "(Unabridged)", ": A Novel", ", Book 2", ", Vol. 1" stripped). Greedy `.*$` on book/vol patterns drops subtitles but that's an intentional search-core reduction.
- `OpenLibraryCoverClient` is fault-tolerant: every HTTP/parse error path returns null (search wrapped in `runCatching`, parse wrapped in `runCatching`, the whole `openLibrary.fetchCover(...)` call is `runCatching{...}.getOrNull()` at the call site) — a parse error cannot abort the enrich loop.
- "Without primary image" SQL is correct: `NOT EXISTS (SELECT 1 FROM images i WHERE i.entity_id = a.entity_id AND i.is_primary)` correctly excludes entities that have a primary row; NULL `is_primary` is not TRUE so correctly counts as "no primary". Partial unique index guarantees ≤1 primary so `findByEntityIdAndIsPrimaryTrue` is well-defined.
- Audiobook genre filter is case-correct: scanner stores genre `name` as-is (NOT lowercased), and the query uses `lower(g.name) IN ('audiobook','audiobooks')` while `isAudiobook()` lowercases the probed genre — both sides handle case. DISTINCT guards against duplicate `entity_genres` rows.
- Cover-cache safe-root wiring is consistent: scanner/enricher/controller all read `yaytsa.image.cover-cache-dir`; controller adds `coverCacheRoot` to `imageRoots`; stored paths are absolute and re-resolved through `resolveServableFile` (`toRealPath()` + `startsWith(safeRoot)`), so materialized rows are both advertised (`imageTags`) and servable — no advertise-but-404 for the happy path. `AudiobookCoverIntegrationTest` asserts this end to end.
- Embedded extraction is idempotent and bounded: content-addressed cache key (`absPath|mtime|size|embedded`), returns cached file if present, atomic temp+move write; gated to audiobook tracks with embedded art; album path falls through to `?: return` when cache dir unset or no art.

## Correctness audit (14cde4d5) — Scout B: client image-gating

**Root cause shared by all findings below:** the diff gates cover requests on `track.AlbumPrimaryImageTag`, but the v2 backend **never populates that field**. Verified in `TrackProjection.kt:11-38` (`Track.toJellyfinBaseItem`) and `JellyfinItemsController.kt:455,507` — the only image signal a v2 item carries is `ImageTags = {Primary: <ownId>}` (set when `coverImagePath != null`); `albumPrimaryImageTag` is declared in the DTO (`JellyfinDtos.kt:14`, `adapter-shared JellyfinDtos.kt:14`) but is assigned nowhere in the backend (`grep 'albumPrimaryImageTag =' yay-tsa-v2/` → zero hits). Therefore on the wire `AudioItem.AlbumPrimaryImageTag` is **always `undefined`** for this server. Additionally the image endpoint `GET /Items/{itemId}/Images/{imageType}` (`JellyfinMediaController.kt:40-76`) **ignores the `tag` query param** (it isn't even a declared parameter) and resolves the cover purely by id — `getPrimaryImage(id)` or embedded artwork, handling both album ids and track ids (`:88-99`). So the pre-change "request by id, no tag" path returned 200, and the new tag-gate suppresses valid covers. The canonical pattern these files were meant to mirror (MediaCard.tsx:51, AlbumCard/ArtistCard, AlbumDetailPage/ArtistDetailPage, usePrefetchArtwork) gates on **`ImageTags?.Primary`** — NOT `AlbumPrimaryImageTag`. The track-image helpers gate on the wrong field.

### 🔴 1 — Offline cover download dead-gated: downloaded tracks lose offline artwork

`apps/web/src/features/offline/stores/offline.store.ts:355`

```ts
if (track.AlbumPrimaryImageTag && !(await store.getCover(coverKey))) {
  const imageUrl = client.getImageUrl(coverKey, 'Primary', { tag: track.AlbumPrimaryImageTag, ... });
```

`coverKeyFor(track)` is `AlbumId ?? Id` (:130-132) — a valid id the backend serves covers for. The pre-change code (8ab416a0) fetched `getImageUrl(coverKey, 'Primary', {tag: undefined})` with NO `AlbumPrimaryImageTag` guard, and because the endpoint ignores the tag, it returned 200 and cached the cover. The new `track.AlbumPrimaryImageTag &&` guard is **always false** for v2, so the entire cover-fetch block is now dead code: **no offline cover is ever cached**. Combined with `TrackList.tsx:74-78` falling back to `getCoverUrl(track)` when offline, downloaded tracks now render the placeholder offline. **Why wrong:** gates on a field that is structurally always undefined. **Fix:** gate on the real cover signal — `if (!(await store.getCover(coverKey)))` and fetch by `coverKey` with `tag: track.ImageTags?.Primary ?? track.AlbumPrimaryImageTag` (tag is cosmetic; the id drives the 200). At minimum drop the `AlbumPrimaryImageTag &&` precondition.

### 🔴 2 — Track-list / player-bar / album-colors covers all fall to placeholder

`apps/web/src/shared/utils/track-image.ts:20-32` (helper) — callers: `TrackList.tsx:61-67`, `PlayerBar.tsx:401-415`, `useAlbumColors.ts:49-55`, all passing `albumPrimaryImageTag: track.AlbumPrimaryImageTag`.

```ts
if (albumPrimaryImageTag) {
  return getImageUrl(albumId ?? trackId, 'Primary', { tag: albumPrimaryImageTag, ... });
}
return getImagePlaceholder();   // <- ALWAYS taken for v2
```

The removed branch was `if (albumId) return getImageUrl(albumId, 'Primary', { maxWidth, maxHeight });` — the path that actually delivered every track cover (request album cover by album id, no tag, endpoint serves 200). Since `AlbumPrimaryImageTag` is always undefined, the helper now returns the CSS placeholder for **every** track in every list, the player bar, and the now-playing color extraction. **Why wrong:** the gate's positive branch is unreachable on this backend; the only working path was deleted. **Fix:** gate on the real signal. Either thread `imageTags?.Primary` into `TrackImageOptions` and gate on it, or restore the id-based fallback: when no tag, still `return getImageUrl(albumId ?? trackId, 'Primary', { maxWidth, maxHeight })` (the endpoint resolves the cover by id, and a track-id request also serves embedded/track-level art for audiobooks). The placeholder should only be the final fallback when neither `albumId` nor `trackId` exists.

### 🔴 3 — Media Session lockscreen artwork never populated

`apps/web/src/features/player/stores/player.store.ts:433-444` (`updateSessionMetadata`)

```ts
let imageUrl: string | undefined;
if (track.AlbumPrimaryImageTag) {
  imageUrl = currentClient.getImageUrl(track.AlbumId ?? track.Id, 'Primary', {
    tag,
    maxWidth: 256,
    maxHeight: 256,
  });
}
// removed: else if (track.AlbumId) { imageUrl = getImageUrl(track.AlbumId, 'Primary', {256x256}); }
```

Same always-undefined field. The deleted `else if (track.AlbumId)` branch was the one that ever produced a URL. Result: `session.updateMetadata({ ..., imageUrl: undefined })` for every track → **OS lockscreen / media-notification artwork is now always blank** on this backend. **Why wrong:** the comment claims it "leaves artwork undefined rather than pointing at a missing cover," but the cover is NOT missing — it's served by id; the gate is reading the wrong field. **Fix:** mirror the helper fix — populate `imageUrl` from `track.AlbumId ?? track.Id` whenever the track has any cover (`track.ImageTags?.Primary`), passing the tag only as a cache hint.

### 🔵 4 — RadioSeedCard gate is too loose (does not actually suppress 404s), but no cover regression

`apps/web/src/features/radio/components/RadioSeedCard.tsx:22-27`

```ts
if (hasError || !seed.albumId || !seed.imageTag) return getImagePlaceholder();
return getImageUrl(seed.albumId, 'Primary', { maxWidth: px, maxHeight: px, tag: seed.imageTag });
```

Here the field IS populated (distinct `/v1` DTO): backend sets `imageTag = track.albumId?.value` (`JellyfinAdaptiveController.kt:699`) — i.e. the album id, not a content hash and **not** a presence signal for an actual cover. So: (a) covers still load for seeds with an album (✅ no regression vs the old `albumId`-based path); but (b) the gate's intent — "only request a cover the seed actually advertises" — is NOT achieved: a seed whose album has no `coverImagePath` still has `imageTag == albumId` (truthy), so the request still fires and still 404s into a ResourceError. The 404-noise goal is unmet for cover-less albums. **Why minor:** no valid cover is hidden; only the noise-suppression objective is partially missed. **Fix (backend, optional):** set `imageTag` to the album id only when the album/track actually has a cover (mirror `coverImagePath?.let { albumId }`), so the client gate becomes meaningful.

### 🔵 5 — Audiobook covers OK (separate code path), confirming the correct pattern

`apps/web/src/pages/AudiobooksPage.tsx:158-159` renders via `MediaCard itemId={book.coverItemId} imageTag={book.coverImageTag}`, where `useAudiobooks.ts:145-146` sets `coverItemId = first.Id ?? albumId` and `coverImageTag = first.AlbumPrimaryImageTag ?? first.ImageTags?.Primary`. Because it falls back to `ImageTags?.Primary` (which v2 DOES populate, = track id) and passes the track id as the image id, audiobook covers render correctly — verified against `AudiobookCoverIntegrationTest.kt` (track-level Primary row → `/Items/{trackId}/Images/Primary` 200 AND `ImageTags.Primary == trackId`). This is the pattern the three regressed call-sites above should have used: **gate on `ImageTags?.Primary`, request by id**. No bug here; included as the reference for the fixes.

**Severity counts (Scout B): 🔴 3 · 🟡 0 · 🔵 2.** GATING-CORRECTNESS GOAL: **NOT MET** — the gate keys off `AlbumPrimaryImageTag`, a field the v2 backend never sets, so it is the always-gated (too-aggressive) failure mode: every track-list cover, every offline cover download, and all Media Session lockscreen artwork silently fall to the placeholder. The endpoint ignores the tag and serves covers by id, so the pre-change id-based requests were correct; the fix is to gate on `ImageTags?.Primary` (matching MediaCard / the audiobook path) and keep requesting by id.

## Correctness audit (14cde4d5) — Scout D: MCP/ML/groupsync/karaoke

Range `git diff 8ab416a0..HEAD`. Build/tests reported GREEN; findings below are runtime/logic correctness, not compile errors.

### HIGH

**H1 — Karaoke vocal-blend: stale previous-track vocals bleed into next track on natural track-end.**
`apps/web/src/features/player/stores/player.store.ts` `loadAndPlay` (lines 649–718) and `packages/platform/src/web/html5-audio.ts` `load()` (340–428) + `play()` (440–484).

The engine's `blendActive` flag is set `false` in only two places: `dispose()` (html5-audio.ts:686) and `exitVocalBlend()` (html5-audio.ts:846). Plain `load()`/`seek()` never reset it. During karaoke, `schedulePreload()` skips preloading (player.store.ts:464-466), so on natural track end `onEnded` ALWAYS takes the non-gapless branch:

```
if (preloader.isReady(next.Id)) { await gaplessTransition(...) }   // never true in karaoke
else { ... await loadAndPlay(next, signal); }                       // player.store.ts:859-864
```

`loadAndPlay` resets only store state (`set({ ..., isKaraokeMode: false })`, line 679) and then:

```
await withTimeout(engine.load(streamUrl), ENGINE_TIMEOUT_MS);   // line 691 — does NOT clear blendActive
...
await engine.play();                                            // line 711
```

`engine.play()` hits the blend branch:

```
await this.audio.play();
if (this.blendActive) {                                         // html5-audio.ts:463 — still true
  this.audioSecondary.currentTime = this.audio.currentTime;
  await this.audioSecondary.play().catch(() => {});             // resumes PREVIOUS track's vocals stem
}
```

`audioSecondary` still holds the previous track's vocals `src` (only cleared in `exitVocalBlend`/`dispose`). Result: the new track's full-mix plays on the primary element WHILE the previous track's vocals stem plays on the secondary at the blend gain — audible double-audio. It self-corrects only once `commitPlaybackSideEffects` → `syncKaraokeForTrack` → `enterVocalBlend(newTrack)` finishes its async stem reload (a network fetch), so the glitch lasts the whole stem-load window on every karaoke track change.
Worse case: re-entry in `commitPlaybackSideEffects` is gated on `get().karaokeEnabled` (line 612). If a track is in per-track karaoke with `karaokeEnabled` false, blend is never re-entered and `blendActive` stays `true` with stale vocals indefinitely.
Why wrong: `loadAndPlay`/`load` implicitly assume the engine is in single-stream mode, but the engine can be left in blend mode. The contract that "load resets to single-stream" is not honored by the engine.
Fix: either (a) have `loadAndPlay` call `exitVocalBlend`/reset blend before `engine.load()` when `engine.isVocalBlendActive?.()`, or (b) make `engine.load()` defensively tear down blend (`blendActive=false`, pause+clear `audioSecondary`, gain→0) at its start. (b) is the robust fix since `load()` is the documented re-entry point.

### LOW

**L2 — Group control-mode change is not pushed live to non-owner members.**
Backend `GroupController.setControlMode` broadcasts `broadcaster.emit(gid, "control_mode_changed", ...)` (GroupController.kt) but the SSE client (`apps/web/src/features/player/stores/group-sync-store.ts` `connectSSE`, lines 150-199) registers listeners for `schedule_changed`, `group_ended`, `member_joined`, `member_left` only — there is no `control_mode_changed` listener. Members learn the new `controlMode` only on snapshot refetch (join, or reconcile on SSE reconnect). Not a security issue: `canControlSchedule` is enforced server-side and only the owner sees the toggle UI (`isOwner && ...`). Purely a live-sync/UX gap.
Fix: add a `control_mode_changed` SSE listener that does `store.setState({ controlMode: parsed.controlMode })`.

**L3 — `sendAction` does not surface 403 (forbidden by control-mode) to the user.**
`group-sync-store.ts` `sendAction` (318-342) refetches only on `String(error).includes('409')`; a 403 from `canControlSchedule` (a member acting while mode='host') falls into the `else` branch → silent `log.player.error`. Tolerable today because the schedule-control UI is owner-gated, but if any member-reachable control surface ever calls `sendAction`, the rejection is invisible. Low.

### NOT BUGS (verified correct)

- **MCP semantic search wiring is correct.** `McpTools.searchLibrary(query, semantic)`: when `semantic`, `embeddingPort.encodeText(query)` → CLAP text vector → `mlQuery.findTracksByClapVector(vector, 20)` → `libraryQueries.getTrack(EntityId(...))`. `HttpEmbeddingClient.encodeText` reads response field `embedding` — matches `TextEmbedResponse{embedding, dimensions}` in `services/audio-ml/app.py:1187`. CLAP text embedding is L2-normalized 512-d (`extractor/embedding_extractor.py:141-162`, `CLAP_DIM=512`), matching `findByClapVector` SQL `CAST(:vec AS vector(512))` with cosine operator `<=>` (correct for normalized CLAP vectors). Disabled/down service: `encodeText` returns null on `!enabled`, network failure, non-2xx, or parse error → `semanticSearch` returns null → `searchLibrary` degrades to lexical `searchText`. No crash. Empty-query guard present (`trimmed.isEmpty()`), empty-vector guard in `findByClapVector`.
- **No cross-user leak / library-scope concern in semantic search** beyond what already exists for lexical search: `findTracksByClapVector` is global over `track_features` (no user filter), but so is the existing lexical `searchText` — library is a single shared read surface in this design. Not a regression.
- **`JellyfinAdaptiveController.searchRecommendations`** degrades correctly: null vector or empty semantic result → lexical `searchText` fallback (comment matches behavior); requires authenticated principal (401 otherwise); `musicSurfaceFilter` applied to both paths.
- **`LlmClient` log-wording change is a lying-comment FIX, not a behavior change.** `LlmOrchestrator` line 121 `llmClient.complete(...) ?: return` — on null it returns early leaving the queue untouched; there is no ML-only re-rank fallback. New text "adaptive DJ rewrite skipped (queue unchanged)" is accurate; old "falling back to ML-only" was stale/deceptive.
- **Group-sync epoch handling is consistent.** SSE `schedule_changed` and `reconcileSnapshot` both gate on strictly-increasing `scheduleEpoch` (group-sync-store.ts:162, 405); `applySchedule` sets `currentEpoch = schedule.scheduleEpoch`. `reconcileSnapshot` rationale (in-memory broadcaster has no replay buffer; refetch on reconnect) is correct. `setControlMode` does optimistic local set with rollback on failure. No off-by-one found.
- **Karaoke gain math is correct.** Instrumental on primary at fixed gain 1.0, vocals on secondary at gain `level∈[0,1]` (`enterVocalBlend` html5-audio.ts:823+, `applyVocalBlendGain` clamps via `setVocalBlend`). level=0 → pure instrumental; level=1 → instrumental+vocals = original mix (stems sum to source). Boundaries clamped in `setVocalBlend` (`Math.max(0,Math.min(1,level))`) and slider divides by 100. Drift watchdog re-locks secondary to primary every 250ms past 0.05s. Deleted `vocal-removal.ts` (phase-cancellation) fully replaced by stem co-play; no dangling imports (removed from platform/src/index.ts; html5-audio.ts import removed). `audio.interface.ts` contract (`enterVocalBlend/setVocalBlend/exitVocalBlend/isVocalBlendActive`) matches the web impl.
- **`schedulePreload` correctly guards against clobbering the vocals stem**: while `isKaraokeMode`, it invalidates and returns without preloading into `audioSecondary` (which holds the vocals stem). This is exactly why H1 leaves a stale stem rather than an empty one.
- **Subsonic `getNowPlaying`/`starredAlbums` additions** are user-scoped via `principal` and behave sensibly (starred albums derived from per-track favorites; pagination drop/take on distinct album ids). Minor: no stable ordering on starred albums, acceptable for Subsonic.

Severity counts: HIGH 1, LOW 2.

---

## 2026-06-20 · 14cde4d5 · finalization increments (karaoke vocal-blend, transfer/remote-command, group control_mode + reconcile, AI/CLAP text-search, unified global search, backend hygiene)

Deterministic layer green before audit: `tsc -b` ✅, frontend `npm run build` ✅, backend `compileKotlin compileTestKotlin spotlessCheck` ✅ — "non-existent API / wrong signature / fake package" class largely cleared by compilers; 6 read-only web-sourced scouts verified logic + dynamic refs only.

### 🔴 Must-fix (genuine logic defect in new code)

1. **DO** — `apps/web/.../stores/group-sync-store.ts:409` — reconnect snapshot reconcile is a **no-op for the exact desync it was added to fix**. Adopts `snapshot.schedule` only when `scheduleEpoch > currentEpoch`, but optimistic `applySchedule` in `sendAction` already bumped `currentEpoch`, so a reconnect snapshot with epoch `<= currentEpoch` is skipped → device keeps playing while group paused. Fix: adopt snapshot schedule unconditionally on reconnect; keep strict-`>` only on the live `schedule_changed` path.

### 🟡 Fixing this pass (cheap, real, our new code)

1. **DO** — `DeviceSessionProjection.register` full-replace wipes `deviceName` to null when a heartbeat's `auth.deviceName` is null → "Unknown Device" resurfaces. Preserve existing non-null name; add `?: clientName ?: "Unknown Device"` fallback in `/Sessions` + device DTO.
2. **DO** — `adapter-shared/HttpEmbeddingClient.kt:61` — wrong-length embedding 500s instead of degrading (`CAST vector(512)` throws, bypassing `null→lexical`). Validate `node.size()==CLAP_DIM`, else log+null.
3. **DO** — `html5-audio.ts:838` `setVocalBlend(NaN)` bypasses clamp → `setTargetAtTime(NaN)` throws (masked by store clamp today). `Number.isFinite` guard. + `:908` drift watchdog lacks stalled-primary guard (`if(audio.paused||readyState<3) return`) → vocal stutter when instrumental rebuffers.
4. **DO** — `useRemoteCommands.ts:42` group SEEK casts `positionMs as number` w/o the `typeof` guard the solo path (:72) has → `NaN` corrupts group anchor for all members. + `:95` untargeted command fails open → ignore engine commands lacking `targetDeviceId`.
5. **DO** — `GlobalSearchBar.tsx:22` `navigate()` per keystroke without `replace:true` → one history entry per char, back-button broken.
6. **DO** — `JellyfinDevicesController` `/commands` SSE endpoint dead/misleading (works only because it shares the `/events` broadcaster) → delete it + comment. + `sendCommand` maps all `CommandResult.Failed`→409; route through `failureTranslator` (expired lease should be 401/403).
7. **DO** — e2e `search.spec.ts:147` expects `search-input` on `/search` (per-page input removed for global bar) → repoint at `global-search-input`/URL flow (legitimate update for intended UI change, not assertion-weakening).

### 🔵 Noted follow-up (not blocking)

Outbox at-least-once: `NEXT`/`PREV` remote command can double-execute in a narrow crash window (no idempotency key on SSE fan-out). Server-side `targetDeviceId` routing (today client-filter; no cross-user leak). Subsonic `getNowPlaying` omits `username`/`minutesAgo`/`playerId`; `getAlbumList type=starred` unstable pagination order + per-track→album approximation. MCP semantic search skips `musicSurfaceFilter`. `ScanStatus.lastCompletedAt` stamped even on failed walk.

### Verified-correct (feared bugs ABSENT)

pgvector cosine `<=>` matches `vector_cosine_ops` HNSW + `vector(512)` + ASC nearest-first. Scan-guard `compareAndSet`+`finally` (no TOCTOU/permanent-block). `control_mode` boolean not inverted, owner-only setter, migration default `host`. WebSocket/STOMP removal clean. LlmClient log string-only. Karaoke engine: instrumental=base@1.0/vocals=variable (not swapped), watchdog interval created+cleared every path, pause/seek/play hit both elements, position preserved on exit, MediaElementSource reused (no node leak), 15ms gain smoothing. vocalBlend `0..1` consistent. STOP halts local; other-device commands ignored; reconnect refetch fires every reconnect not initial. transferLease STOP→OLD owner, best-effort can't roll back aggregate, outbox fields round-trip. Nav removal clean, query URL-encoded, SearchPage reacts to `?q=`, semantic opt-in preserved.

_Scouts/synthesis: 6/1 (arbiter consolidation)._

---

## 2026-06-21 · 6373e4b6 · full-file re-review resolution (verified every prior finding against HEAD before acting)

Re-verified the ENTIRE log (2026-06-14 + 2026-06-20 entries) against current code via 4 read-only cluster agents — findings go stale, most prior ones already landed.

**2026-06-14 entry — status against HEAD:**

- #1 LLM-DJ freshness gate, #2 CI exclude-paths, #3 DeviceDto/isOnline/transfer-button, #4 transferHere playback, #5 play() resume position (code+test), #6 RewriteQueueTail cap+dedup — **all FIXED-ALREADY** in later commits. Verified with file:line.
- #7 `yaytsa.llm.system-prompt` read-but-never-configured (dead branch) — **LEFT**: latent, only bites when `LLM_ENABLED` (off in prod); wiring a real prompt is a product decision, not a correctness bug.
- #8 spend-audit token columns unpopulated — **LEFT**: write-path missing but only matters when LLM-DJ live AND wanting per-decision cost; LiteLLM key-spend is the live cost signal (QA.md). Additive follow-up, not "has to fix".
- #9 getPlayQueue `mapNotNull` — **premise wrong**: `current` is a track-id (spec), not an index, so no out-of-bounds; only a benign "current may dangle if its track was deleted". **LEFT**.
- #10 V005 Flyway comment, #11 lease-transfer cross-user — **DON'T** (unchanged).

**2026-06-20 🔵 follow-ups — fixed in 6373e4b6:**

- **MCP semantic/lexical search bypassed `musicSurfaceFilter`** → red-line + audiobook leak on the MCP protocol surface (user red-line contract bypass, violates one-core invariant). Fixed via a proper shared extraction: new `core-application:recommendation` module `MusicSurfaceFilter`, injected into BOTH `JellyfinAdaptiveController` (private filter methods deleted) and `McpTools` (semantic + lexical paths). DRY, not copy-paste.
- **NEXT/PREV remote command double-execute** (outbox at-least-once, no idempotency key) → stamped a stable `commandId` on `DomainNotification.RemoteCommand` (`JpaRemoteCommandPort`), round-tripped through `JpaOutboxPort`, carried into the SSE `command` event (`RemoteCommandSseBridge`), deduped client-side in `useRemoteCommands.ts` (bounded 64-id Set, only when present → back-compat).
- **`LibraryScanner.lastCompletedAt` stamped on a FAILED walk** → moved the stamp below the `if (!walkCompleted) return` guard (honest operational signal).
- **Also fixed the CI-reddening flake** `ItemsPaginationIntegrationTest > ExcludeGenres`: brittle exact `== allTotal - 2` over the shared-Testcontainer GLOBAL `TotalRecordCount` (other classes seed audiobook rows concurrently) rebounded to tag-scoped-names-exact + bounded total. Test-isolation fix; no production code touched for it.

**2026-06-20 🔵 — LEFT (justified):**

- Server-side `targetDeviceId` SSE routing — client filter already **fails closed**, no cross-user leak; per-device keying is a 4-layer SSE-contract change for zero correctness gain. Defer until the broadcaster is next refactored.
- Subsonic `getNowPlaying` missing `username`/`minutesAgo`/`playerId` — additive, single-user/single-server in practice, no consumer. Subsonic `getAlbumList type=starred` ordering — already deterministic given a fixed favorites set (favorites carry a stable `position`); only generic offset-pagination drift remains.

Verify: backend `spotlessApply`/`compileKotlin`/`compileTestKotlin` + `:app:test` 202 pass (incl. ArchUnit on the new module + Spring wiring); frontend `type-check`+`build` green.

_Scouts/synthesis: 4 read-only verifiers + 1 fix executor._
