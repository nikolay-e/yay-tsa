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
