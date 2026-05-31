# Correctness Audit — yay-tsa

Find where code doesn't do what it claims: incorrect logic, task drift, deceptive naming, lying comments, almost-right regex/SQL, error handlers that hide errors, off-by-one, wrong JOINs, boundary failures.

Severity: 🔴 wrong behavior in normal usage · 🟡 wrong in edge cases · 🔵 misleading names/comments.

Date: 2026-05-29

---

## Round 1 — Core queue / shuffle / repeat / HTTP retry (TS)

### F1 — 🟡 `hasPrevious()` lies when `repeatMode === 'one'`

`packages/core/src/player/queue.ts:248` returns `true` for both `'all'` and `'one'`, but `previous()` (queue.ts:283–284) returns the _same_ item under repeat-one without navigating. UI enables a "previous" button that is a no-op.

### F2 — 🟡 `next()` with repeat-one skips `recordCurrentToHistory()`

`queue.ts:259–261` bypasses history recording on repeat-one; turning repeat-one off later leaves the looped track absent from history.

### F3 — 🔵 Jitter uses discrete `+1` trick vs continuous `random*cap`

`api.client.ts:187` `Math.floor(Math.random() * (maxDelay + 1))` — comment says continuous AWS full-jitter; harmless but misleading.

### F4 — 🔵 DELETE both `isIdempotent` and `isMutating`

`api.client.ts:218,221` — DELETE gets retried AND gets an idempotency key; contradicts `isMutating` naming.

Fisher-Yates (queue.ts:444–448) correct. Ticks (TICKS_PER_SECOND=10_000_000) correct.

## Round 1 — Auth & token correctness

### F1 — 🔴 JellyfinAuthFilter skips `revoked` check, admits revoked tokens

`adapter-jellyfin/.../JellyfinAuthFilter.kt:61` gates only on `!expired`. `ApiTokenAuthFilter.kt:37` gates on `!revoked && !expired`. Logout sets `revoked=true` but Jellyfin filter ignores it → revoked tokens accepted across `/Users`,`/Items`,`/Audio`,`/Karaoke`.

### F2 — 🔴 JellyfinAuthFilter inverts null-default on `expired`

`JellyfinAuthFilter.kt:55–60` defaults `expired=false` when apiToken null; `ApiTokenAuthFilter.kt:35–36` defaults `true`. Asymmetric security decision on same token.

### F3 — 🟡 SHA-256 hash compared with Kotlin `==` (non-constant-time)

`ApiTokenAuthFilter.kt:33`, `JellyfinAuthFilter.kt:54` `it.token == hashedToken`. Use `MessageDigest.isEqual`.

### F4 — 🟡 WebSocketAuthInterceptor skips expiry AND revocation

`infra-notifications/.../WebSocketAuthInterceptor.kt:27–30` only checks `isActive`; revoked/expired token holds WS connection indefinitely.

### F5 — 🔵 BCrypt `gensalt()` default cost 10, not claimed strength 13

`JellyfinAdminController.kt:75–79,116–120` `BCrypt.gensalt()` with no arg → cost 10.

## Round 1 — Library query SQL & pagination

### F1 — 🟡 `parentId` branch ignores StartIndex/Limit — full album dump

`JellyfinItemsController.kt:116–121` fetches all album tracks ignoring pagination; TotalRecordCount==Items.size hides it.

### F2 — 🟡 `Recursive=true&ParentId=<artistId>` routes to album query

`JellyfinItemsController.kt:37,116,141–158` — `recursive` never read; parentId branch calls `browseTracksByAlbum` with an artist UUID → empty list.

### F3 — 🔵 `OffsetBasedPageRequest.getPageNumber()` integer-division truncation

`OffsetBasedPageRequest.kt:20` — cosmetic; getOffset() used directly so results unaffected.

### F4 — 🔵 ILIKE on `name`, `search_text`/pg_trgm trigram index unused

`LibraryEntityRepository.kt:33–43`.

## Round 1 — Playback domain invariants (OCC/idempotency/lease)

### F1 — 🔴 Idempotency replay returns stale current-state, not original result

`PlaybackUseCases.kt:53–56`, `PreferencesUseCases.kt:47–48` re-fetch current aggregate on replay; stored `resultVersion` ignored. Violates "same key → same result".

### F2 — 🟡 `setFavorite` no-op stores pre-command version as resultVersion

`PreferencesHandler.kt:41` returns snapshot.version; idempotency store records stale version.

### F3 — 🟡 GroupController.leave/end no ownership check

`GroupController.kt:124–141` — any authenticated user can evict any device / dissolve any group.

### F4 — 🔵 GroupSyncService uses `Instant.now()`/SQL `now()` directly

`GroupSyncService.kt:80,115,139,184,213` bypass CommandContext.requestTime (app-layer, arguably out of scope).

### F5 — 🔵 OCC `expectedVersion` defaults to INITIAL(0)

`AdapterCommandContextFactory.kt:18–19` — omitting arg → spurious Conflict on existing aggregates.

## Round 1 — Adapter protocol translation

### F1 — 🔴 MPD `idle` fires immediate fake `changed: player`

`MpdCommandHandler.kt:70` — should long-poll/block; instant fake event makes clients spin.

### F2 — 🔴 MPD `pause` ignores `[0|1]` argument

`MpdCommandHandler.kt:56,218` — `pause 0` (resume) treated as toggle.

### F3 — 🔴 Jellyfin `playlistItemId` = trackId (wrong identity)

`JellyfinPlaylistsController.kt:139` — duplicate tracks get duplicate PlaylistItemId; remove-by-EntryId deletes wrong/multiple entries.

### F4 — 🟡 MPD `UnsupportedByProtocol` → ACK code 5 (UNKNOWN CMD)

`MpdFailureTranslator.kt:18` — signals command doesn't exist.

### F5 — 🟡 Subsonic `ChildElement` missing required `isDir`/`parent`

`ChildElement.kt:6–25`.

### F6 — 🟡 Subsonic Conflict/StorageConflict → error code 0 (generic)

`SubsonicFailureTranslator.kt:15–16` indistinguishable from crash fallback.

### F7 — 🔵 ProblemDetail `detail` = `status.reasonPhrase` (redundant)

`ProblemDetailErrorController.kt:27`.

Ticks (TICKS_PER_MS=10_000) correct.

## Round 1 — Streaming range/seek & media path safety

### F1 — 🔴 Null `safeRoot` bypasses ALL path validation

`MediaStreamController.kt:21` + `MediaPathSafety.resolveServableFile:29` `if (safeRoot != null && !real.startsWith(safeRoot))` — when music-path unconfigured, any canonical path servable (e.g. /etc/shadow).

### F2 — 🟡 TOCTOU window between `Files.exists(NOFOLLOW)` and `toRealPath()`

Both MediaPathSafety files lines 18–23. Mitigated by startsWith only when safeRoot non-null.

### F3 — 🟡 Multi-range request silently downgraded to full-file 206

`MediaStreamController.kt:70` split on `-` mis-parses `bytes=0-99,200-299` → returns full file as 206.

### F4 — 🔵 Two MediaPathSafety files byte-for-byte identical (drift risk)

`infra-media/MediaPathSafety.kt` & `adapter-jellyfin/MediaPathSafety.kt`.

Range arithmetic (end-start+1, 206, suffix/open-ended) correct.

## Round 1 — Scanner & ML worker

### F1 — 🔴 Affinity watermark read outside txn → additive double-counting

`AffinityAggregator.kt:32–33` MAX(updated_at) read then UPSERT not in same txn; `ON CONFLICT DO UPDATE SET` (lines 98–106) purely additive → re-processed windows increment counters again.

### F2 — 🔴 `repairExistingTrackLinkage` never overwrites wrong album link

`LibraryWriter.kt:309` guard `albumId == null` only fills nulls; stale/wrong album persists.

### F3 — 🟡 NULL-source_path ghost rows fragile via `Array<Any?>` projection

`LibraryEntityRepository.kt:115–121`, `LibraryWriter.kt:425–428` mapNotNull silently drops bad UUID casts.

### F4 — 🟡 pgvector queries pre-filter `IS NOT NULL` defeats HNSW index

`TrackFeaturesJpaRepository.kt:13–19` — post-filter returns < lim silently; seed subquery evaluated twice.

### F5 — 🔵 `deleteOrphanArtists` not @Transactional (asymmetric vs albums)

`LibraryWriter.kt:444`.

## Round 1 — PWA web layer

### F1 — 🔴 `engine.isPlaying` read as property, not called → stall-recovery dead

`apps/web/src/features/player/stores/player.store.ts:608` `const paused = !engine.isPlaying;` — `isPlaying()` is a method (platform/src/audio.interface.ts:56, html5-audio.ts:536). Reference always truthy → `paused` always false → `recoverStalledAdvance()` guard never fires.

### F2 — 🟡 `seek()` skips `engine.seek()` while controller active

`player.store.ts:903–906` — UI/Media Session show seek position but engine plays from 0 on load complete.

### F3 — 🔵 `getNextPageParam` reads TotalRecordCount from lastPage only

`useInfiniteLibraryQuery.ts:50–53` — stale total if library changes mid-scroll.

### F4 — 🔵 SW audio rule matched by URL substrings not Content-Type

`apps/web/vite.config.ts:59–62`.

### F5 — 🔵 `redactSecrets` regex breaks on quoted URL (`SECRET"`)

`packages/core/src/internal/utils/redact.ts:1–7`.

## Round 2 — Verification of R1 critical findings (VERIFIER)

All nine R1 🔴 candidates opened at cited lines:

- **#1 JellyfinAuthFilter skips `revoked`**: CONFIRMED at filter level (line 61 gates only `!expired`). [NOTE: disputed by Cross-Cut agent — revocation may be enforced at repo layer; see below + R4.]
- **#2 inverted null-default `expired`**: CONFIRMED — `?:false` vs `?:true`; unmatched-hash path concern.
- **#3 idempotency replay stale state**: CONFIRMED — only `newVersion.value` stored, replay re-`find()`s live aggregate.
- **#4 MPD idle+pause**: CONFIRMED — line 70 instant fake event; line 218 `Pause()` drops args.
- **#5 playlistItemId=trackId**: CONFIRMED — line 139; remove (line 178) resolves entryIds→TrackId, deletes all dups.
- **#6 null safeRoot bypass**: CONFIRMED, exploitable when `media.enabled:false` (Helm default) → MUSIC_PATH unset → safeRoot null.
- **#7 affinity double-count**: CONFIRMED, edge-triggered (no @Transactional, additive ON CONFLICT, watermark gap). 🔴→🟡.
- **#8 repairExistingTrackLinkage**: CONFIRMED — line 309 fills nulls only; artist linkage (313) DOES overwrite.
- **#9 engine.isPlaying as property**: CONFIRMED — interface method (audio.interface.ts:56), `!engine.isPlaying` always false.

## Round 2 — Missed correctness issues (GAP-FINDER)

### R2-1 — 🟡 MCP server responds to JSON-RPC notifications

`adapter-mcp/.../McpController.kt:81-84` returns a JsonRpcResponse for `notifications/initialized`; spec says a server MUST NOT respond to a notification (no `id`). Handler keys off `method`, never checks absence of `id`.

### R2-2 — 🔴 OutboxPoller re-delivers on partial-batch failure

`infra-notifications/.../OutboxPoller.kt:14-23` — `@Transactional` loop sends WS message (irreversible) then sets `publishedAt`. If entry N throws, txn rolls back, discarding publishedAt for 0..N-1 already sent → duplicate delivery next tick. Side-effect inside dedup transaction.

### R2-3 — 🟡 RewriteQueueTail dedups entry IDs (always-fresh UUIDs) not trackIds

`AdaptiveHandler.kt:129-132` checks `it.id` (random UUID, never collides); duplicate `trackId` from LLM unchecked (`LlmOrchestrator.kt:278-283`).

### Verified-correct (no finding)

HttpFailureTranslator.statusFor exhaustive (no else); PlaylistHandler.reorder dup-safe; Outbox query `FOR UPDATE SKIP LOCKED` correct; adaptive position renumbering contiguous.

## Round 2 — Severity calibration & dedup (CALIBRATOR)

Calibrated severities: Auth-F1/PWA-F1/MPD-F2 remain 🔴 (pending R4 auth resolution); Stream-F1 null-safeRoot →🟡 (misconfig only); Jellyfin-F3, MPD-F1, Playback-F1, Scanner-F1/F2, Auth-F2 →🟡 (conditional).

**Root-cause clusters:**

- Auth-F1+F2+F3, WS-F4 = **divergent token-validation paths**; should share one `validateToken()` helper.
- Stream-F1+F2+F4 = **two byte-identical MediaPathSafety files**; consolidate to one module.

**Likely false positives:** Core-F3/F4 (cosmetic), Library-F3 getPageNumber (getOffset used directly), OCC-F5 (adapters set version explicitly).

**Integration-test catchable:** Auth logout→401; MPD pause 1/pause 0 state; PWA backgrounded auto-advance; Scanner concurrent-signal no-double-count; Jellyfin dup-track remove-one.

## Round 2 — Auth/protocol consistency matrix (CROSS-CUT)

**KEY CORRECTION:** All filters call `AuthQueries.findByApiToken` → `JpaUserRepository.findByApiToken` → `findByTokenAndRevokedFalse(hash)` (`JpaUserRepository.kt:48-49`). **Revocation is enforced at the SQL layer — revoked tokens return null**, so per-filter `revoked` checks are effectively dead code, NOT an exploitable bypass. `AuthLogoutIntegrationTest.kt:44-50` proves logout→`/Items` 401. There is **no token-validation Caffeine cache** (CLAUDE.md claim unimplemented) → no stale-cache bug class.

| Entry point                    | token-found   | revoked     | expired          | isActive | device-binding |
| ------------------------------ | ------------- | ----------- | ---------------- | -------- | -------------- |
| ApiTokenAuthFilter:29-37       | ✓             | ✓ DB+filter | ✓ (null→expired) | ✓        | captured only  |
| JellyfinAuthFilter:51-61       | ✓             | ✓ DB only   | ✓ (null→false)   | ✓        | captured only  |
| SubsonicAuthFilter:34-52       | bcrypt        | n/a         | ✗                | ✓        | none           |
| WebSocketAuthInterceptor:27-32 | ✓ DB(revoked) | ✓ DB        | ✗ **none**       | ✓        | none           |

### Findings

- **R2-1 🟡 WS ignores expiry** — `WebSocketAuthInterceptor.kt:27-32` no `expiresAt` check; expired-unrevoked token holds WS session.
- **R2-2 🔵 (DOWNGRADE Auth-F1/F2 from 🔴)** — revoked rejected at DB layer; logout test passes. Filter divergence is a drift smell, not exploit.
- **R2-3 🟡 device-binding decorative** — `deviceId` captured (ApiTokenAuthFilter:42, JellyfinAuthFilter:63) but never compared to request on any protocol. Stolen token usable anywhere. "Device-bound" = bound-at-issuance only.
- **R2-4 🟡 non-constant-time hash compare** — `==` on SHA-256 hex; low exploitability (hash of 256-bit secret).
- **R2-5 🔵 Subsonic token-mode `t`+`s` always fails** (bcrypt irreversible); only `p=` plaintext works.

## Round 3 — Prosecution

Top charges (consequence-first):

1. 🔴 **PWA stall-recovery dead** (player.store.ts:608) — `engine.isPlaying` read as property; stalled track never auto-advances. Core player function broken on lock-screen/flaky network.
2. 🔴 **Outbox duplicate notifications** (OutboxPoller.kt:16-22) — publish() then save(publishedAt) in one txn; partial-batch failure re-sends already-delivered WS events. Breaks "one consistent state."
3. 🔴 **MPD `pause 0` treated as toggle** (MpdCommandHandler.kt:56,218) — broken transport for ncmpcpp/mpc.
4. 🔴 **Jellyfin remove-one-track deletes all duplicates** (JellyfinPlaylistsController.kt:139,178) — playlistItemId=trackId → unintended data loss.
5. 🟡 Idempotency replay returns live state not stored result (PlaybackUseCases.kt:53-56).
6. 🟡 Null safeRoot path-validation bypass (MediaPathSafety.kt:29) — exploitable on default Helm config.
7. 🟡 WebSocket auth ignores expiry (WebSocketAuthInterceptor.kt:27-28).
8. 🟡 parentId album browse ignores StartIndex/Limit (JellyfinItemsController.kt:116-121).

**Disputed Auth-F1:** Prosecution concedes — `JpaUserRepository.kt:49` `findByTokenAndRevokedFalse` rejects revoked tokens at SQL layer; logout test passes. Downgrade to 🔵 drift smell.

## Round 3 — Defense

- **Auth-F1/F2 REBUTTED → 🔵**: DB query `findByTokenAndRevokedFalse` makes filter-level revoked/expired omissions unreachable dead code; `AuthLogoutIntegrationTest:44-50` passing.
- **Stream-F1 DOWNGRADE → 🟡 dev-only**: `source_path` is scanner-written only (never user-supplied); with `media.enabled:false` (chart default) no volume mounted → empty library → no rows resolving to sensitive files. Exploit needs contradictory config. Still add `requireNotNull(safeRoot)` fail-closed.
- **Idempotency-F1 CONCEDED 🟡**: violates literal contract but replay usually hits unchanged aggregate; harmful only on interleaved command under same key.
- **CONCEDED 🔴 (no defense)**: PWA-F1 isPlaying, MPD-F2 pause, Jellyfin-F3 playlistItemId, OutboxPoller R2-2 redelivery — reachable in normal usage.

## Final Verdict

### TL;DR

The backend's domain core (OCC, idempotency keys, leases, failure mapping) is largely sound, but correctness defects cluster at the **edges where code translates between systems**: protocol adapters (MPD, Jellyfin playlists), the JS↔platform boundary (a method called as a property), the outbox→WebSocket hop, and the worker→DB aggregation. Four confirmed 🔴 produce wrong behavior in normal usage. The headline auth "revoked token" scare is a false alarm — revocation is enforced at the SQL layer.

### Top Issues (fix immediately)

1. ✅ **`apps/web/.../player.store.ts:608`** — `const paused = !engine.isPlaying;` negates a **method reference** (`isPlaying(): boolean` — audio.interface.ts:56, html5-audio.ts:536), always truthy → `paused` always `false` → `recoverStalledAdvance()` guard (line 610) can never fire. Stalled-track auto-recovery (lock screen / flaky network) is dead code. Fix: `!engine.isPlaying()`.
2. ✅ **`infra-notifications/.../OutboxPoller.kt:18-21`** — single `@Transactional` loop calls `publisher.publish()` (irreversible WS send) **then** `save(publishedAt)`. If any later entry throws, the whole txn rolls back and re-sends already-delivered notifications next tick. Move the side-effect out of the dedup transaction, or mark each entry in its own transaction before publishing.
3. ✅ **`adapter-mpd/.../MpdCommandHandler.kt:56,218`** — `pause()` discards the `[0|1]` argument and always issues `Pause` (toggle). Per MPD spec `pause 0`=resume, `pause 1`=pause. Explicit resume/pause from ncmpcpp/mpc misbehaves.
4. ✅ **`adapter-jellyfin/.../JellyfinPlaylistsController.kt:139,178`** — `playlistItemId = entry.trackId.value`; remove resolves `EntryIds`→`TrackId`. A playlist with the same track twice has duplicate PlaylistItemIds, so removing one copy deletes **all** copies. Needs a per-slot entry identifier.

### High-value 🟡

- **Idempotency replay** (PlaybackUseCases.kt:53-56, PreferencesUseCases.kt:47-48): replay re-`find()`s live aggregate instead of replaying stored result — violates "same key → same result" when state interleaves.
- **Null `safeRoot`** (MediaPathSafety.kt:29): path containment skipped when `music-path` unset (Helm `media.enabled:false` default). Not exploitable in normal deploy (paths are scanner-written), but add a fail-closed `requireNotNull(safeRoot)`.
- **WebSocket auth ignores expiry** (WebSocketAuthInterceptor.kt:27-32): expired (unrevoked) token holds a live session indefinitely.
- **`parentId` album browse** (JellyfinItemsController.kt:116-121): ignores StartIndex/Limit, dumps full album, `TotalRecordCount==Items.size` masks it.
- **Affinity double-count** (AffinityAggregator.kt:32-33): watermark read outside the additive UPSERT txn → signals in the gap re-summed.
- **`repairExistingTrackLinkage`** (LibraryWriter.kt:309): only backfills null albumId; a wrong non-null album link persists after retag/move (artist link at 313 does overwrite).
- **device-binding decorative** (ApiTokenAuthFilter.kt:42, JellyfinAuthFilter.kt:63): `deviceId` captured but never compared per-request — "device-bound tokens" is bound-at-issuance only.
- **MPD `idle` fake event** (MpdCommandHandler.kt:70); **Subsonic ChildElement missing `isDir`/`parent`** (ChildElement.kt); **MCP responds to notifications** (McpController.kt:81-84); **adaptive tail dedups entry UUIDs not trackIds** (AdaptiveHandler.kt:129-132).

### Systemic Patterns

1. **Translation-boundary bugs** — every confirmed 🔴 lives where one model is mapped to another (TS method→property, domain event→WS, MPD verb→command, playlist entry→track). Root cause: thin adapters written by mechanical field-mapping without round-trip tests.
2. **Divergent duplicated logic** — two byte-identical `MediaPathSafety` files; two auth filters encoding the same decision differently. Consolidate to single sources.
3. **Side-effect inside the transaction that gates it** — OutboxPoller publishes then marks in one txn; affinity reads watermark outside the txn that advances it. Both are at-least-once/double-count hazards.

### False Positives from Round 1

- **Auth-F1 (revoked tokens admitted)** — NOT exploitable. `JpaUserRepository.findByApiToken:49` → `findByTokenAndRevokedFalse`; `UserRepositoryTest.kt:107` ("returns null for revoked token") + `AuthLogoutIntegrationTest` confirm logout→401. JellyfinAuthFilter's missing `revoked` check is unreachable drift, not a bypass → 🔵.
- **Auth-F2 (inverted `expired` null-default)** — `?:false` for null `expiresAt` correctly means "no expiry = not expired"; ApiTokenAuthFilter's `?:true` errs toward rejection. Asymmetric but not exploitable → 🔵.
- **Jitter `+1` / DELETE naming** (api.client.ts) — cosmetic, behavior correct.
- **`getPageNumber()` truncation** (OffsetBasedPageRequest.kt:20) — `getOffset()` used directly; results unaffected.
- **OCC `expectedVersion` default INITIAL** — adapters pass version explicitly; omitted-arg path not demonstrably reached.

### Verdict

A solid hexagonal core wrapped in adapters that mostly map fields correctly but leak four real correctness bugs at the seams — fix the method-call typo, the outbox ordering, the MPD pause arg, and the playlist entry identity; the auth alarm is a false positive.

## Audit Log

- Date: 2026-05-29
- Project: /Users/nikolay/yay-tsa
- Skill: /review-correctness
- Total findings: 4 🔴 confirmed · ~12 🟡 · ~9 🔵 (incl. 5 downgraded/false-positive)
- Agents used: 8 + 4 + 2 + 1 = 15
