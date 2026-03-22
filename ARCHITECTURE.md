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

## Backend: Concurrency & Resource Management

### AsyncConfig Executors

| Executor                  | Type                             | Limit | Backpressure                      |
| ------------------------- | -------------------------------- | ----- | --------------------------------- |
| `applicationTaskExecutor` | SimpleAsyncTaskExecutor(virtual) | 50    | Blocks submitting thread at limit |
| `signalAsyncExecutor`     | SimpleAsyncTaskExecutor(virtual) | 10    | Blocks submitting thread at limit |
| `recommendationExecutor`  | SimpleAsyncTaskExecutor(virtual) | 5     | Blocks submitting thread at limit |

All three use `SimpleAsyncTaskExecutor` with virtual threads and `concurrencyLimit`. Spring manages lifecycle. `@Async` without qualifier uses `applicationTaskExecutor`. Injection sites use `Executor` (not `ExecutorService`) — `CompletableFuture.runAsync/supplyAsync` for fire-and-forget tasks.

### QueueSseService

`ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>`. Dead emitters removed in three places: `broadcast()` IOException catch, `sendHeartbeats()` IOException catch, `removeEmitter()` callback. Heartbeats every 15s.

Bounded by `MAX_SESSIONS = 100`. New connections rejected with error when limit reached.

### KaraokeService State Machine

`processingJobs: Caffeine Cache<UUID, JobEntry>` (maximumSize 1000, expireAfterWrite 2h). JobEntry is immutable record — `withStatus()` returns new instance. Explicit `cleanupExpiredJobs()` runs every 5m for finer-grained TTL (1h for completed, 10m60s for stale PROCESSING).

States: NOT_STARTED -> PROCESSING -> READY | FAILED

`getStatus()` returns `notStarted()` for FAILED — treats failure as transient for retry UX.

Auto-recovery: if DB shows not-ready but stems exist on disk, recovers without re-processing.

`separationExecutor` is fixed pool of 2 — if both slots stuck, subsequent requests queue indefinitely.

### MediaScannerTransactionalService Caches

Three Caffeine caches with size limits and TTL: `artistIdCache` (10k, 2h), `albumIdCache` (50k, 2h), `genreIdCache` (1k, 2h). Eviction is safe — DB fallback on cache miss.

### StreamingService TOCTOU

`resolveFile()` validates path safety via `NOFOLLOW_LINKS` before opening. Race window between check and open — caught by `NoSuchFileException` handler. ETag based on mtime + fileSize only.

---

## Backend: Scrobble Logic (SessionService)

Scrobble threshold (Last.fm-compatible):

- `duration >= 30s` AND
- `played >= 50% of duration` OR `played >= 240s`

`play_count` increments ONLY on scrobble. `completed` = played >= 95%. `skipped` = !scrobbled && !completed.

`reportPlaybackStart()` finalizes previous track BEFORE starting new one — prevents lost play state on track switch. Finalization is synchronous within transaction.

Session keyed by `(userId, deviceId)`. Duplicate creation handled by `DataIntegrityViolationException` catch + retry lookup.

---

## Security

### Auth Flow

1. `EmbyAuthFilter` extracts token from header (`X-Emby-Authorization`) or query (`api_key`)
2. `authService.validateToken()` — Caffeine-cached DB lookup
3. Valid token: SecurityContext set. Invalid: request proceeds unauthenticated (endpoint enforces 401)
4. Skip paths: login, public info, health, swagger, CORS preflight

Token is 256-bit opaque (SecureRandom), device-bound, one per user per device.

### Endpoint Security

Everything requires authentication EXCEPT: `/Users/AuthenticateByName`, `/System/Info/Public`, `/manage/health`, documentation endpoints, `OPTIONS` preflight.

`/manage/**` (actuator) is DENY ALL — even authenticated users cannot access metrics/env.

### Frontend Token Storage

- `rememberMe=false` (default): `sessionStorage` — cleared on tab close
- `rememberMe=true`: `localStorage` — persists across browser closes

Logout clears: both storage modes, service worker caches (`yaytsa-*`), TanStack Query cache, DJ session state.

### Retry Strategy (HTTP Client)

- GET/DELETE: retry on network errors + 500 + 502-504 (idempotent)
- POST/PUT: retry ONLY on 502-504 (non-idempotent, fail fast on 500/network)
- Backoff: exponential with full jitter, capped at 20s (AWS pattern)
- 401: triggers auth error callback (auto-logout) before throwing

Stream URLs use `api_key` query param because `<audio>` and `<img>` tags don't send custom headers.

---

## Scan Pipeline (FileSystemMediaScanner)

Five phases, sequential:

1. Walk filesystem, collect audio files
2. Process each file: add/update DB (skip if mtime + size unchanged)
3. Remove deleted items (paths not in `processedPaths`)
4. Transcode non-native codecs to FLAC (bounded by semaphore)
5. Scan missing artwork

Single-threaded walk (intentional — disk I/O). `processedPaths` is `HashSet` (not thread-safe, but single-threaded).

Tag extraction: jaudiotagger. CP1251 repair for garbled Cyrillic tags. Track number prefix stripping for filename-based titles. `search_text` denormalized column updated at scan time.

Transcode order: validate output (size + ffprobe duration) BEFORE deleting original. On validation failure: delete transcode output, keep original.

---

## Known Issues (To Fix)

### Karaoke Regressions

- `setKaraokeMode()` synchronous but `ensureAudioContext()` can fail silently
- Toggle during load creates race between `loadAndPlay` and `commitPlaybackSideEffects`
- SSE resource leak in `KaraokeController` (abandoned connections, thread pool exhaustion)
- `karaokeFailedTrackIds` persists across user sessions
