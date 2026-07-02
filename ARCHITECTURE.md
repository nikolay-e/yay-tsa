# ARCHITECTURE.md — Invariants & Non-Obvious Behaviors

> This document captures invariants, state machine rules, and gotchas discovered through reviews and debugging.
> **Read this before modifying any subsystem.** Breaking these invariants causes regressions that are hard to diagnose.

---

## Audio Engine (html5-audio.ts)

### Dual Element Invariant

`this.audio` is ALWAYS the active playback element. `this.audioSecondary` is ALWAYS the preload target. `performElementSwap()` swaps references and re-attaches dispatch handlers. External code never touches secondary directly.

After swap: old primary (now secondary) has gain 0, new primary (was secondary) has handlers attached.

### WebAudio Lazy Init

WebAudio graph initializes ONLY on `getAudioContext()` or `setKaraokeMode(true)`. `play()` does NOT init WebAudio. Without WebAudio, audio routes directly to speakers (no gain nodes). Once initialized, `webAudioInitialized = true` permanently — graph is never torn down.

Volume control path depends on state:

- No WebAudio: `element.volume` directly
- WebAudio active: `masterGain.gain` node

If `masterGain` is null but `webAudioInitialized = true`, something catastrophically failed — volume falls back to `element.volume` which outputs OUTSIDE the WebAudio graph.

### Load Chain Serialization

`loadPromiseChain` serializes load/play operations. Each `load()` calls `cancelCurrentLoad()` which silently swallows "Load cancelled" rejection via `.catch()`. Without this, a cancelled load's rejection poisons the chain for subsequent loads.

`preloadedUrl` and `preloadPromise` must be BOTH set or BOTH null. Partial state breaks `isPreloaded()` and `seamlessSwitch()`.

### Approaching-End Threshold

`approachingEndThresholdMs` is set once in constructor and never changes. Cannot be adjusted mid-playback. UI changes to approaching-end behavior require a new engine instance.

### Duration Stability

`stableDuration` is captured at load time and used for all duration queries. Browser's `audio.duration` can fluctuate during buffer stalls — caching at load-complete provides stable values.

### Muted Attribute vs Volume

Secondary element stays muted during `seamlessSwitch()` until after `play()`. This is NOT for silence — Chrome's power-saving policy blocks `play()` on volume=0 elements. The muted attribute doesn't affect MediaElementAudioSourceNode routing.

---

## Player Store (player.store.ts)

### suppressNextEnded Guard

After gapless transition executes, `suppressNextEnded = true` prevents the old audio element's `ended` event from triggering double-advance. Reset only when next `onEnded` fires. If this flag is missing or reset prematurely, tracks advance twice.

### Repeat Mode Dual Sync

`repeatMode` must be synced to queue in BOTH `playTrack()` and `playTracks()` via `queue.setRepeatMode(repeatMode)`. Store's `repeatMode` is authoritative; queue reads it in `peekNext()` and `resolveNextItem()`. Missing either sync breaks repeat logic.

### Preload Invalidation Rule

After ANY mode change (shuffle, repeat, karaoke), BOTH `preloader.invalidate()` AND `schedulePreload()` must be called:

- Invalidate without reschedule = no next track preloaded
- Reschedule without invalidate = preloads stale data

Karaoke switch does NOT preload next track immediately — preload is deferred to after karaoke switch completes via `.finally()`. This prevents dual-element conflicts.

### Consecutive Load Failures

`consecutiveLoadFailures` resets only on successful load or `autoAdvanceOnError()` reset. NOT reset on pause/resume. If 3 tracks fail, then user pauses, then plays a new track — counter is still 3 and first failure hits max immediately.

### karaokeFailedTrackIds Persistence

Never auto-cleared except on `stop()`. Failed karaoke tracks are skipped on re-enable. Survives app reload (in-memory Set, not storage — cleared on full page reload but not on track change).

### Engine Timeout Retry

`loadAndPlay()` retries ONCE on timeout with 2s delay. Only first timeout triggers retry (`retryCount < 1`). Any other error is fatal and triggers `autoAdvanceOnError()`.

### Queue History Trimming

After every track load, `queue.trimBeforeCurrent()` deletes all items before current from BOTH `items` and `originalOrder`. Breaks backward navigation — by design, trim erases history.

---

## Queue (queue.ts)

### Dual Queue Representation

`items` = display order (may be shuffled). `originalOrder` = persistent order (never shuffled). Both must contain the exact same tracks by ID, just in different order when shuffle is active. `addToQueue()`, `removeAt()`, `insertAt()`, `moveItem()` must update BOTH.

### currentIndex Maintenance

After any modification (remove, insert, move, shuffle toggle), `currentIndex` must be `< items.length` (or -1 if empty). All mutation methods adjust currentIndex to maintain this.

### peekNext() Contract

`peekNext()` must return the EXACT item that `next()` would advance to. Mismatch breaks gapless preload — preloaded track won't match next track played.

- Repeat 'one': returns current item (repeat means "don't advance")
- Repeat 'all' after last: wraps to first item
- Repeat 'off' after last: returns null

### Shuffle + Insert Behavior

`insertAt()` during shuffle appends to `originalOrder`, not at insertion index. When shuffle is toggled OFF, inserted item's position is at the END of original order, not the insertion point. `moveItem()` operates on `items` only — does NOT update `originalOrder`. Move reverts on shuffle toggle.

### Empty Queue Transition

Once queue is empty (`items.length = 0`), `currentIndex = -1`. Subsequent `addToQueue()` sets `currentIndex = 0`. "Queue becomes empty" is a one-way transition for index tracking.

---

## DJ Session (session-store.ts)

### Start Appends, Not Replaces

`startSession()` calls `appendToQueue()`, not `playTracks()`. If music is already playing, DJ queue appends to existing queue. User must manually navigate to DJ tracks.

### Single Restore Guard

`restoreInProgress` flag blocks concurrent `restoreSession()` calls. Safety timer forcibly clears after 10s. Without guard, concurrent calls silently drop and session never restores.

### Restore Timing Dependency

`restoreSession()` requires auth client to be available. If called before auth setup completes, `getDjService()` returns null and restore silently fails. Must be called AFTER auth is established.

### Signal Throttle

`lastSignalTimestamps` keyed by `sessionId:signalType`. Same signal type throttled to 500ms. Different types fire independently. Map is never cleared — stale entries persist across session boundaries.

### Refresh Queue Deduplication

`refreshQueue()` filters out existing tracks by ID. Only NEW tracks from backend are appended. If backend removes a track from queue, local queue still has it.

---

## Backend: Yay-Tsa v2 (Kotlin Hexagonal Monolith)

> The v1 Java backend was retired 2026-05-16. The sections below describe `yay-tsa-v2/`. Full manifesto: [`yay-tsa-v2/CLAUDE.md`](yay-tsa-v2/CLAUDE.md).

### Eight Bounded Contexts, One Write-Side Each

Write contexts (aggregates with OCC + idempotency): **auth** (`UserAggregate`), **playback** (`PlaybackSessionAggregate`), **adaptive** (versioned adaptive queue), **preferences** (`UserPreferencesAggregate`), **playlists** (`PlaylistAggregate`). Read-only worker-owned contexts: **library** (scanner), **ml** (embeddings/affinity), **karaoke** (stems/lyrics timing). Each context owns its PostgreSQL schema (`core_v2_*`); cross-context data flows only through application-layer ports and preloaded `deps` — never direct imports (enforced by ArchUnit).

Layering: `core-domain` (pure handlers, zero I/O/framework) → `core-application` (use cases: load snapshot, check idempotency, call handler, persist with OCC, write outbox in the same transaction) → `infra-*` (JPA, Flyway, workers) → thin protocol adapters (`adapter-jellyfin`, `adapter-opensubsonic`, `adapter-mpd`, `adapter-mcp`) with zero business logic.

### Command Invariants (violating any is a bug)

- **OCC everywhere**: every aggregate carries a version; persistence is a conditional `UPDATE ... WHERE version = :expected`; concurrent writers get typed `Failure.Conflict`, never silent lost updates.
- **Client-driven idempotency**: every external write carries an `idempotencyKey`. Same key + same payload → replays the stored `CommandResult`; same key + different payload → `Failure.InvariantViolation`.
- **Single-writer device lease (playback only)**: one device at a time may mutate queue/state/position; TTL-based takeover on expiry. The adaptive context deliberately has NO lease — it is OCC + a separate `queueVersion` counter, which is what lets a background extender and the user safely write the same session.
- **Pure domain handlers**: `(snapshot, command, context, deps) → result | Failure`. No I/O, no `Instant.now()` — time enters only via `CommandContext.requestTime`.
- **Typed failures, not exceptions**: `Conflict / Unauthorized / NotFound / InvariantViolation / UnsupportedByProtocol / StorageConflict` are normal business results; adapters map them to protocol errors (RFC 7807 on the Jellyfin surface).
- **Transactional outbox**: notifications are written in the same transaction as the aggregate change; a poller delivers them after commit (WebSocket fan-out). Commit ⇒ delivered, rollback ⇒ never leaves the system.

### Playback Position: Lazy Computation (Variant B)

The aggregate stores `lastKnownPosition` + `lastKnownAt`. Current position is computed on read (`PLAYING`: add elapsed wall time; `PAUSED`/`STOPPED`: as stored). DB writes happen only on pause/seek/track-change/stop — not during continuous playback.

### Workers Populate Read-Only Schemas

Workers bypass `core-domain` and write directly to their own schemas (they must never call domain handlers): `infra-library-scanner` (filesystem walk → `library`, single writer, upsert semantics, no OCC), `infra-ml-worker` (Discogs/MusicNN/CLAP/MERT embeddings + scalar features → `ml`, HNSW indexes), `infra-karaoke-worker` (Demucs in-process or GPU HTTP sidecar → `karaoke`), `infra-llm` (adaptive queue tail rewrites via the in-cluster LiteLLM gateway, gated on `LLM_ENABLED`, graceful ML-only fallback when off).

### Streaming

Byte-range serving (RFC 9110) writes directly to the servlet response; paths are validated against the configured library root with NOFOLLOW_LINKS (TOCTOU mitigation).

---

## Security

### Auth Flow (v2)

1. Token extracted from any of: `Authorization: Bearer`, `X-Emby-Token`, `X-Emby-Authorization`, `?api_key=` query (load-bearing for `<audio src>`/`<img>` which can't send headers), `yay_token` cookie
2. Validation: SHA-256 of the presented token looked up in DB, Caffeine-cached (hashed cache key)
3. Tokens are 256-bit opaque (SecureRandom), device-bound, one per user per device; passwords BCrypt strength 13
4. Auth endpoints rate-limited per (IP, path); `/Admin/*` requires `isAdmin`

### Frontend Token Storage

- `rememberMe=true` (default): `localStorage` — survives reload, PWA reopen, frontend updates
- `rememberMe=false`: `sessionStorage` — tab-scoped

On startup the persisted token is re-validated against `/Users/Me`: a confirmed 401 clears auth and routes to login; transient network/5xx errors keep the user signed in (no auto-logout on backend restart).

Logout clears: both storage modes, service worker caches (`yaytsa-*`), TanStack Query cache, DJ session state.

### Retry Strategy (HTTP Client)

- GET/DELETE: retry on network errors + 500 + 502-504 (idempotent)
- POST/PUT: retry ONLY on 502-504 (non-idempotent, fail fast on 500/network)
- Backoff: exponential with full jitter, capped at 20s (AWS pattern)
- 401: triggers auth error callback (auto-logout) before throwing

Stream URLs use `api_key` query param because `<audio>` and `<img>` tags don't send custom headers.

---

## Library Scanner (infra-library-scanner)

Single-writer filesystem walk with upsert semantics into `core_v2_library` (no OCC — worker-owned schema). Skip-unchanged via mtime + size. Tag extraction via JAudioTagger with folder/filename fallbacks; placeholder tags treated as missing. Deletion reconcile is scoped by `library_root` — an empty walk must never mass-delete, and renames sever favorites/playlists/resume references (see PLAN.md technical debt).
