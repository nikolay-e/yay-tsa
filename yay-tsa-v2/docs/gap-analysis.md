# Yaytsa Core v2 — Gap Analysis

Auto-generated gap analysis relative to the Architecture Manifesto.
Status: verified 2026-04-11.

## Critical Gaps (no external access to Core)

| Gap | Module | Status | Impact |
|-----|--------|--------|--------|
| Jellyfin adapter (Yaytsa protocol) | `adapter-jellyfin/` | Built — Jellyfin standard + custom extensions, all write contexts + library | — |
| OpenSubsonic adapter | `adapter-opensubsonic/` | Built — MVP with ping, browsing, search, playlists, favorites stubs | No Symfonium/DSub/Feishin support |
| MCP adapter | `adapter-mcp/` | Built — MCP JSON-RPC endpoint with 9 tools (search, playback, browse, playlists) | ~~No LLM agent integration~~ Mitigated |
| MPD adapter | `adapter-mpd/` | Built — TCP server on port 6600, core MPD commands (status, play, pause, search, browse) | ~~No ncmpcpp/mpc/Cantata support~~ Mitigated |
| Auth/security infrastructure | — | Built — Bearer token filter + Spring Security + Subsonic token auth | ~~No token validation on incoming requests~~ Mitigated |
| Media streaming | `infra-media/` | Built — file streaming with Range support, Subsonic /rest/stream | No audio file serving to clients |

## Worker Gaps (read-only data sources unpopulated)

| Gap | Module | Status | Impact |
|-----|--------|--------|--------|
| Library scanner | `infra-library-scanner/` | Built — basic filesystem walker, upsert by path | Library schema stays empty — no tracks to play |
| ML worker | `infra-ml-worker/` | Built — scheduled feature extractor with Python script integration | ~~No embeddings, no taste profile~~ Mitigated |
| Karaoke worker | `infra-karaoke-worker/` | Built — scheduled Demucs processor with stem separation | ~~No vocal separation, karaoke mode unavailable~~ Mitigated |
| LLM orchestrator | `infra-llm/` | Built — signal-driven orchestrator with Anthropic API client | ~~Adaptive context has no driver~~ Mitigated |

## Infrastructure Gaps

| Gap | Location | Status | Impact |
|-----|----------|--------|--------|
| Flyway multi-schema config | `app/application.yml` | Built — 9 schema beans in FlywayConfig | Migrations won't run on app startup |
| Production datasource config | `app/application.yml` | Built — HikariCP, JPA validate, Jackson | App won't connect to PostgreSQL |
| `play_history` write-side | — | Built — PlayHistoryWritePort + JPA implementation | ~~Play history table never written to~~ Mitigated |
| WebSocket fan-out | `infra-notifications/` | Built — STOMP WebSocket with LoggingNotificationPublisher fallback | Outbox events logged, not pushed to clients |
| `DeviceSession` projection | — | Built — in-memory ConcurrentHashMap projection | ~~No runtime device tracking~~ Mitigated |
| Docker/docker-compose | — | Built — pgvector/pgvector:pg16 | No containerized deployment |
| CI integration test setup | `.github/ci.yml` | Built — pgvector/pgvector:pg16 Docker service | ~~Testcontainers tests may not run in CI~~ Mitigated |

## Domain Gaps

| Gap | Context | Status | Impact |
|-----|---------|--------|--------|
| `RecordPlaybackSignal` command | adaptive | Built — command, handler, signal write port, REST endpoint | Client can't send signals (skip, complete, thumbs) — adaptive pipeline broken at first link |
| `PlaybackSignal` write-path | adaptive | Built — via RecordPlaybackSignal command | ~~`playback_signals` table has entity but no command/use case~~ Mitigated |
| Query model formalization | shared | `supportedQueries` always empty | CQRS Q-side not formalized, reads are ad-hoc methods |

## Test Gaps

| Gap | Module | Status |
|-----|--------|--------|
| `AdaptiveSessionRepository` OCC integration test | `infra-persistence:adaptive` | Missing (other contexts have it) |
| `NativeProtocolCapabilities` completeness test | `app` | **Fixed** — added in this review |
| `OutboxPoller` test | `infra-notifications` | Missing |
| `IdempotencyCleanupJob` test | `infra-persistence` | Missing |
| `SpringTransactionalCommandExecutor` integration test | `infra-persistence` | Missing |

## Pre-existing Test Failures

| Module | Failure | Root Cause |
|--------|---------|------------|
| `infra-persistence:ml` | JPA binding errors | pgvector/null type binding issues with Testcontainers |
| `infra-persistence:adaptive` | JPA binding errors | String array (`[Ljava.lang.String;`) type binding issues |
