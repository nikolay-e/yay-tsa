# PRODUCT.md — yay-tsa product audit (intended vs actual)

Append-only cumulative log. Each finding carries evidence on BOTH sides (intended X `quote@source` ↔ actual Y `file:line`), a severity capped by intent-strength, and an acceptance check. Severity legend: 🔴 stated/required behavior missing or a real contract broken · 🟡 accidental behavior / under-specified gap / stale-doc-vs-code conflict / invariant-at-risk · 🔵 "is this intended?" from code shape only.

This product has a **rich written contract** (README, project + backend `CLAUDE.md`, the DESIGN manifesto, `QA.md` behavior specs, GitHub issues) — so this is NOT a "no external contract found" case. Headline result: **the load-bearing guarantees are genuinely wired, not schema-shipped-dead.** OCC, idempotency, single-writer lease, transactional outbox, typed failures, externalized time, RFC 7807 convergence, binary Range/206 + TOCTOU, the LLM-DJ end-to-end chain, HNSW similarity radio, affinity signal→score, scanner ghost-reconcile, and the metadata-enricher throw-on-transient contract were each confirmed with a live read/write path + a DB-backed test. The divergences below are real but mostly at the edges (one adapter under-delivers its manifesto promise; several docs drifted from better-than-documented behavior).

---

## 2026-06-20 — Audit pass 1 (review-pyramid 4→2→1; 5 scouts → 2 adversarial verifiers → synthesis)

### A. Must-have intent unmet (🔴)

#### 🔴 1 — MCP adapter delivers only 2 of its 5 manifesto-promised capabilities (3 missing)

- **Intended X** (HUMAN-WROTE-IT): "MCP adapter — … Claude or any MCP-aware agent can browse the library, **manage the queue**, control playback, **modify the preference contract, and steer adaptive behavior** through tool calls." — `yay-tsa-v2/CLAUDE.md:59` (the module-authoritative manifesto). Project `CLAUDE.md:88` independently promises "edit playlists/preferences".
- **Actual Y**: `adapter-mcp/.../McpTools.kt:37-147` registers 9 tools, all read-or-basic-playback: `search_library, get_playback_state, play, pause, skip_next, skip_previous, browse_artists, get_album, list_playlists`. `preferencesUseCases` and `playlistUseCases` are injected but `@Suppress("unused")` (`McpTools.kt:30-33`) — never called. No adaptive context is even a dependency. `McpProtocolCapabilities.kt:17-24` whitelists only `Play, Pause, SkipNext, SkipPrevious` — so even a hand-crafted preference/adaptive/queue command is rejected `Failure.UnsupportedByProtocol`. **Missing: preference-contract editing, adaptive-steering, AND queue-management (no add/clear/reorder tool).** Only "control playback" and "browse library" are delivered.
- **Angle**: missing required behavior. **Adversarially verified → CONFIRMED** (no doc anywhere scopes MCP to playback-only; the core use cases already exist and are injected).
- **Acceptance check**: `tools/list` returns a tool that calls `PreferencesUseCases.execute(UpdatePreferenceContract(...))` and a tool that calls `AdaptiveUseCases` (start session / rewrite tail); `McpProtocolCapabilities.supportedCommands` includes those commands; an MCP `tools/call` for "set preference contract" succeeds end-to-end. **Needs a product decision** (below) on build-vs-amend before fixing.

### B. Accidental behavior / under-specified / stale-doc conflicts (🟡)

#### 🟡 2 — "No offline playback" is false; the PWA ships a complete, on-by-default offline engine

- **Intended X** (HUMAN-WROTE-IT): "NetworkOnly for audio streams (**no offline playback**)." — `CLAUDE.md` ("PWA and Mobile Strategy"), repeated in the security/perf model.
- **Actual Y**: `apps/web/vite.config.ts:33,63` hands `/Audio/.../stream` to `audio-offline-sw.js` _instead of_ NetworkOnly; `apps/web/public/audio-offline-sw.js:92-118` serves audio from IndexedDB with real `206`/`Content-Range`/`Accept-Ranges`/`416`. Fully surfaced & on by default: `DownloadButton` (`TrackList.tsx:236`), `DownloadTracksButton` (`AlbumDetailPage.tsx:182`), `OfflineManager` (`SettingsPage.tsx:227`), a dedicated `path:'offline'` route (`app/App.tsx:130-134`), player routes through `useOfflineStore.getPlaybackUrl` (`player.store.ts:473,651,873,1004`); defaults `autoDownloadFavorites:true, autoCachePlayed:true, maxCacheBytes:2GB` (`offline.store.ts:91-96`).
- **Angle**: stale-doc-vs-code conflict (the behavior is the _better_ one; the doc lies). **Verified → CONFIRMED.**
- **Acceptance check**: doc describes the IndexedDB Range-served offline engine instead of "no offline playback". (Safe to fix — pure doc correction, no product decision.)

#### 🟡 3 — Per-device live now-playing is an admitted stub; device list omits now-playing fields

- **Intended X** (HUMAN-WROTE-IT): "a single, consistent music state visible across every protocol and device" (`CLAUDE.md`); "`broadcastDeviceState` must include `nowPlayingItemName` alongside `nowPlayingItemId`" (`QA.md:121`); QA.md flow #6 expects per-device state.
- **Actual Y**: `JellyfinDevicesController.kt:63-83` `/v1/me/devices/events` emits only `ready` + keepalives — self-labeled "quiet placeholder until the bridge controller forwards those events here." `device_state_changed`/`DeviceEventBroadcaster`/`broadcastDeviceState` exist **nowhere** in `yay-tsa-v2` (grep-zero). The PWA subscribes `es.addEventListener('device_state_changed', …)` (`useDeviceEvents.ts:64`) — it never fires. `device.service.ts:19-27` also omits `nowPlayingItemId/Name/positionMs` from the list mapping, so per-device now-playing is never populated even on refetch.
- **Adversarially verified → DOWNGRADED from 🔴**: device _discovery + online/offline status_ DO work via heartbeat poll (`useDeviceHeartbeat.ts:5-20` POSTs every 15s; `DevicesPanel.tsx:163-167` re-fetches on open; `device.service.ts:11-12` recomputes `isOnline` from a 45s window). QA.md flow #6's "second tab appears within ~15s" is _met by the heartbeat_. Only the **live SSE push of per-device now-playing/position** is unbuilt.
- **Angle**: missing (sub)behavior vs forward-referenced-but-never-built bridge. **Needs a product decision** (build the WS→SSE bridge + populate now-playing, or accept poll-only and amend QA.md flow #6 / line 121).
- **Acceptance check**: session A reports `/Sessions/Playing` → session B's open DevicesPanel shows A with track name + position within ~15s.

#### 🟡 4 — Karaoke vocals toggle is specified but the frontend never built it

- **Intended X**: QA.md flow #5 (HUMAN-WROTE-IT) — "vocals toggle switches `/instrumental` ↔ `/vocals`." The backend HAS the route (`/Karaoke/{trackId}/{status|instrumental|vocals}`, confirmed wired).
- **Actual Y**: frontend karaoke is binary instrumental on/off only. `api.client.ts:642` has `getInstrumentalStreamUrl` but no `getVocalStreamUrl`; grep for a `/vocals` stream builder across core/platform/web = 0; `player.store.ts:1335` `toggleKaraoke` flips instrumental↔normal with no third mode.
- **Angle**: missing required behavior vs over-specified QA flow. **Needs a product decision**: build the vocals toggle (backend already serves it) or amend QA.md flow #5 to "instrumental on/off".
- **Acceptance check**: with karaoke on, a vocals control switches the stream `/instrumental`↔`/vocals`; OR QA.md flow #5 no longer claims it.

#### 🟡 5 — Karaoke HTTP-separator path marks `readyAt` without validating stems exist on disk

- **Intended X**: "If the backend can't see the files, `getStatus()` → `validateStemFilesExist()` resets `karaokeReady=false`." — `QA.md:108`.
- **Actual Y**: the production HTTP-separator path `KaraokeProcessor.kt:85-102` writes `KaraokeAssetEntity(... readyAt = clock.now())` straight from `result.instrumentalPath/vocalPath` with no `Files.exists()` check; the local-Demucs path (`:130-167`) _does_ validate. Asymmetric — a track is advertised READY before the read side can confirm the file, degrading to a dead toggle / stream-time 404.
- **Angle**: do-not-change-invariant at risk / write-side inconsistency.
- **Acceptance check**: separator returns paths to non-existent files → asset must NOT be marked `readyAt` (or `getStatus` immediately reports not-ready).

#### 🟡 6 — LLM `prompt_tokens`/`completion_tokens` audit columns never populated

- **Intended X**: decision "logged in `llm_decision_log` with **full audit trail: prompt hash, token counts, latency**, applied edits, validation result." — `yay-tsa-v2/CLAUDE.md:287`. (`QA.md:214` already self-discloses these are unpopulated.)
- **Actual Y**: `LlmOrchestrator.kt:191-207` `recordDecision` sets `promptHash`+`latencyMs` but never token counts; `LlmClient.parseCompletion:114-132` ignores the OpenAI response `usage` object; columns (`V001__adaptive_schema.sql:55-56`) stay NULL.
- **Angle**: stale-doc/partial-vs-promised. Small fix (thread `usage.*` through). Safe to fix.
- **Acceptance check**: after one DJ decision, `prompt_tokens/completion_tokens` are non-null and match LiteLLM `usage`.

#### 🟡 7 — Doc-vs-doc drift: LLM-DJ provider ("with Claude" / "local stub")

- **Intended/contract**: `CLAUDE.md` says "infra-llm orchestrates the adaptive queue **with Claude**"; `QA.md:20` matrix says "LLM-DJ is a **local stub**."
- **Actual Y**: neither is true. `LlmClient.kt:77` calls the in-cluster LiteLLM OpenAI gateway, model `GPT-5.4 Mini`; the orchestrator is a fully-wired billed `@Scheduled` worker. `QA.md:201-214` is the correct description; the other two lines are stale.
- **Angle**: stale-doc conflict (two docs disagree with the code and each other). Safe to fix.
- **Acceptance check**: `CLAUDE.md` "with Claude" → "via in-cluster LiteLLM (GPT-5.4 Mini)"; QA.md matrix "local stub" → "wired, gated on `LLM_ENABLED`".

#### 🟡 8 — Enforcement/guard-rail gaps (the manifesto says "enforced by ArchUnit"; partly it isn't)

- `ArchitectureTest.kt:349-367` "adapters must not call domain handlers" lists opensubsonic/mcp/mpd but **omits `adapter-jellyfin`** — the PWA-facing adapter most likely to grow logic (verified it does NOT currently violate; missing guard, not a breach).
- Manifesto Rule 6 "Workers bypass core-domain" is loose: `infra-llm` legitimately imports core-domain _commands/value-types_ (`LlmOrchestrator.kt:11-21`); no handler call, no ArchUnit test. Reword to "workers must not call domain handlers."
- Rule 5 positive constraint (persistence may import domain/X + app/X/port) claimed "enforced" but only the inverse is tested; SLF4J in the purity claim (`CLAUDE.md:223`) has no ArchUnit rule.
- **QA.md:136 lists "ArchUnit guards every injected `MlQueryPort` has a reachable `@Repository` impl"** as a required guard — it is **absent**. Today every port has an impl (verified), but this is the build-time defense against the documented "schema ships dead" regression.
- **Angle**: characterizing/doc-vs-enforcement; acceptance = add the missing rules or soften "enforced".

### C. Lower-confidence observations (🔵)

- 🔵 9 — `packages/platform/src/web/vocal-removal.ts` (`VocalRemovalProcessor`) is exported (`index.ts:16-17`) but imported by nothing — a client-side karaoke processor superseded by the server instrumental path. Decide: wire as offline/no-stem fallback (see Opportunities) or delete.
- 🔵 10 — Subsonic `star`/`unstar` accept `albumId`/`artistId` params but silently drop them; only track favorites persist (`SubsonicController.kt:414-418,442-449`). Fidelity gap vs OpenSubsonic clients that star albums/artists.
- 🔵 11 — `adapter-mcp/.../McpToolRegistry.kt` holds only DTOs, not a registry (the real registry is in `McpTools.kt`). Misleading name; rename `McpDtos.kt`.
- 🔵 12 — MPD `MpdCommandHandler.kt:58-86` holds mutable adapter-local `playlistVersion`/`observedQueueSignature` (a `@Component` singleton) to synthesize MPD's monotonic `playlist:` version — adapter-resident derived state (thin-adapter borderline) with a single-pod assumption (resets on restart, not replica-shared). Defensible (MPD wire protocol demands it) but a judgment call.

### D. Do-not-change invariants surfaced (name them so a later "cleanup" doesn't break them)

- **MPD is unauthenticated and runs every command as a shared `mpd-default` user — safe ONLY because there is no public ingress** (`MpdCommandHandler.kt:46`, ClusterIP-only). Exposing the MPD port publicly = full auth bypass. Hard invariant.
- **Bearer-only survives the Cloudflare/Traefik proxy**; `api_key` query param is load-bearing for `<audio src>`/`<img>` (can't set headers) — `QA.md:104-105`.
- **Ticks = `durationMs * 10_000`** wire format (`Constants.kt:7`); **PascalCase response keys** for Jellyfin compat; **Subsonic lives at `/rest/*`** (nginx must proxy, keep Range) — wire/URL contracts external clients depend on.
- **`TotalRecordCount` on `/Items` must be a DB count, not `items.size`** — PWA infinite-scroll stops at `loaded >= totalRecordCount` (`QA.md:141`).
- **`ddl-auto: validate` in prod** (own DDL via Flyway) — `update` is a foot-gun.

### E. Needs a human answer (product decisions — do NOT guess)

1. **MCP scope (🔴 #1)**: build the preference-contract + adaptive-steering + queue-management tools (use cases already exist & injected), or downgrade the manifesto's MCP promise to "browse + control playback"?
2. **Device sync (🟡 #3)**: build the WebSocket→SSE bridge + populate per-device now-playing, or accept poll-only discovery and amend QA.md flow #6 / line 121?
3. **Karaoke vocals (🟡 #4)**: build the frontend vocals toggle (backend already serves `/vocals`), or amend QA.md flow #5 to "instrumental on/off"?

---

## OPPORTUNITY (not a requirement) — net-new, anchored, severity-less; human is the gate

1. **Wire `VocalRemovalProcessor` as the offline / no-stem karaoke fallback.** Anchor: it's a complete Web-Audio karaoke processor already shipped but unused (🔵 #9), and the PWA now has a full offline engine (🟡 #2) where server-side stems are unreachable — client-side cancellation would give karaoke a graceful offline degrade. VERIFY(gate: user wants this).
2. **Per-device "now playing" in the device list via the existing WebSocket fan-out.** Anchor: `WebSocketNotificationPublisher` already pushes playback state and the device-events SSE stub explicitly references a "bridge controller" that would forward it (🟡 #3) — bridging it turns the DevicesPanel into a real cross-device remote, the headline "consistent state across every device" experience. VERIFY(gate: user wants this).
