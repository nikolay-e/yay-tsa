# CLAUDE.md

> Extends [../CLAUDE.md](../CLAUDE.md) - inherits workspace conventions (integration tests only, no docstrings, Russian communication)

## Project Overview

Yay-Tsa is a self-hosted music streaming system: a custom Jellyfin-compatible media server written in Java paired with a portable React PWA client. The project is structured as an npm workspaces monorepo for the frontend layers, with a separate Maven backend and an optional Python karaoke service.

The system is designed around two hard problems: **fast, flexible queries over large music libraries** and **correct, efficient audio streaming with seek support**.

## Common Commands

```bash
# Development
npm run dev                    # Build core+platform, start Vite dev server with HMR
npm run build                  # Production build (core → platform → web)

# Code Quality
npm run type-check             # TypeScript type checking (strict mode)
npm run lint                   # Prettier check
npm run format                 # Prettier write
npm run pre-commit             # Run all pre-commit hooks manually

# Testing
cd packages/core && npm run test:integration    # Integration tests against local server
cd apps/web && npm run test:e2e                 # Playwright E2E tests

# Docker (starts frontend, backend, database)
docker compose up              # Development with HMR
docker compose --profile test up    # Run E2E tests

# Kubernetes (via GitOps)
# See /Users/nikolay/code/gitops/helm-charts/yay-tsa/
# Deployment managed via Argo CD (gitops repository)
```

## Architecture: Three-Layer Portability Model

The frontend is split into three npm packages with **strict unidirectional dependency flow**: Core → Platform → Web. This is not organizational convenience — it's a deliberate portability strategy.

### Layer 1: Core — Zero-Dependency Business Logic

The core package has **zero production dependencies** (only TypeScript and test tooling as dev deps). It contains all business logic that is inherently platform-agnostic: the HTTP client with Emby-compatible auth headers, service abstractions for library queries and playback reporting, a PlaybackQueue state machine with shuffle/repeat logic, and all shared TypeScript types.

**Why this matters**: The exact same queue logic, auth flow, and API client work identically whether consumed by a React web app, a React Native mobile app, or a Tauri desktop app. The core never imports anything browser-specific, framework-specific, or platform-specific. Type safety at the boundary prevents accidental coupling.

**Key design decisions in core**:

- **State machine over event emitter**: PlaybackQueue is a pure state machine — `next()` returns the new item deterministically, it doesn't emit events. This makes it testable without UI, predictable across platforms, and free of subscription accumulation bugs.
- **Constructor dependency injection**: All services receive the API client via constructor. This keeps services decoupled from client lifecycle and makes the dependency graph visible in type signatures.
- **Exponential backoff with full jitter**: The HTTP client implements AWS-recommended retry strategy — selective retries only for idempotent operations on network/gateway errors. Non-idempotent operations fail fast.
- **Strict error hierarchy**: Typed error classes (AuthenticationError, NetworkError) inheriting from a base MediaServerError enable precise error handling at each layer without string matching.

### Layer 2: Platform — Interface-Driven Adapters

The platform package defines **contracts** (TypeScript interfaces) for platform-specific capabilities and provides implementations for each target. Currently only web (HTML5 Audio, Media Session API, Web Audio) is implemented.

**Why interfaces, not just implementations**: The AudioEngine interface is the boundary between "what the player needs" and "how the browser does it." Future platforms (ExpoAudioEngine for React Native, TauriAudioEngine for desktop) implement the same interface without touching core or UI logic.

**Non-obvious implementation details**:

- **Promise chain serialization**: The HTML5 audio engine serializes load/play operations through a promise chain. Browsers can cancel play() during load(), or queue multiple play() calls — the chain ensures operations happen in order and stale loads are discarded via symbol-based cancellation tokens.
- **Dual audio elements**: Primary element for active playback + secondary for preloading enables seamless crossfade transitions and karaoke mode switching without audible gaps.
- **Error sanitization**: API keys and tokens are redacted from error messages before logging, preventing credential leaks via error tracking.

### Layer 3: Web — Thin Reactive Composition

The React layer is deliberately thin — it composes core services and platform adapters via Zustand stores, handles routing, and renders UI. Almost no business logic lives here.

**Feature-first organization**: Auth, player, and library are isolated feature modules with their own stores, hooks, and components. Features communicate only through store subscriptions and React Router navigation.

**Three-tier state architecture**:

- **Server state** (TanStack React Query): Library items, album details, search results — cached, deduplicated, with infinite pagination. Stale-while-revalidate keeps the UI responsive while background fetches update data.
- **Client state** (Zustand): Authentication, playback, UI preferences — atomic stores with selective subscriptions that prevent unnecessary re-renders.
- **High-frequency state** (separate timing store): Current playback position updates at ~60fps. This is isolated into its own micro-store with requestAnimationFrame batching to prevent the entire player from re-rendering every frame.

## Architecture: Custom Jellyfin-Compatible Backend

### Why Not Just Use Jellyfin?

Jellyfin is a monolithic media server supporting video, TV, movies, music, and books. Yay-Tsa replaces it entirely for the music streaming use case. The frontend doesn't know it's talking to a custom server — the API is identical at the surface level the client uses.

**Reasons for building from scratch**:

- **Scope reduction**: Music-only design eliminates 80% of Jellyfin's complexity. Fewer moving parts means fewer bugs, faster queries, and a smaller operational footprint (~5MB jar vs ~1GB Jellyfin).
- **Query optimization**: Custom PostgreSQL schema with trigram indexes for fuzzy search, composite indexes tuned for the exact filter combinations the UI uses, and JPA Specifications for type-safe dynamic queries. Jellyfin's generic item store can't be this selective.
- **Token model**: Device-bound opaque tokens with immediate revocation instead of stateless JWTs. For a single-service deployment, server-validated tokens are operationally safer and simpler. JWT can be introduced later if cross-service validation becomes a requirement.
- **Virtual threads**: Java 21 virtual threads (JEP 444) enable thread-per-request that scales blocking I/O (filesystem, JDBC, FFmpeg) without reactive complexity. Imperative code with real stack traces, comparable throughput to async for I/O-bound workloads.
- **Full code ownership**: No external upgrade dependencies, no plugin compatibility concerns, no inherited technical debt.

### Backend Design: Ports and Adapters

The server follows hexagonal architecture: controllers handle HTTP, domain services contain business logic, and infrastructure adapters handle persistence, filesystem, transcoding, and image processing. Ports (interfaces) define boundaries between domain and infrastructure.

**Key architectural decisions**:

- **PostgreSQL-only** (no multi-DB abstraction): Trigram indexes, recursive CTEs for hierarchies, CITEXT for case-insensitive usernames, partial indexes on non-revoked tokens. The database does what it's good at — no lowest-common-denominator SQL.
- **RFC 9110-compliant streaming**: Zero-copy file serving via FileChannel.transferTo(), proper byte-range support (206 Partial Content), ETag-based caching, path traversal protection. Optional X-Accel-Redirect delegates to nginx for high-throughput Kubernetes deployments.
- **Database-level cascade cleanup**: Triggers automatically delete orphaned albums when their last track is removed, orphaned artists when their last album is removed, and orphaned genres with no items. This is transactionally safe and race-condition free — no application-level coordination needed.
- **Scrobble threshold**: Playback marked as "played" only if >50% of duration OR >240 seconds, whichever comes first. This matches industry-standard scrobble logic.
- **Caffeine caching**: Token validation, item lookups, and image responses are cached with short TTL. Eliminates database round-trips for the hottest paths.

### Karaoke: Optional Audio Separation

Vocal-instrumental separation is provided by an optional sidecar service running Meta's Hybrid Demucs model via Python FastAPI. The backend coordinates processing, stores stems on a shared volume, and serves instrumental/vocal tracks through the standard streaming API.

**Why a separate service**: The ML model requires ~5GB download and 4GB RAM. Making it optional (Docker Compose profile) means the core system stays lightweight. GPU acceleration is supported but not required — ARM-native CPU mode works on Apple Silicon.

## Technology Choices and Trade-offs

### Frontend: React 19 + Zustand + Tailwind

| Decision     | Choice               | Alternative Considered         | Reasoning                                                                                                                                                                     |
| ------------ | -------------------- | ------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Framework    | React 19             | Svelte                         | Mature ecosystem (React Router 7, TanStack Query), larger community, team familiarity. Svelte would save ~40KB but the <150KB target is achievable with React.                |
| State        | Zustand              | Redux, Context                 | 2KB bundle, minimal boilerplate, per-store granularity with selective subscriptions, no provider wrapping. Redux is overkill; Context requires restructuring for performance. |
| Styling      | Tailwind CSS 4       | CSS Modules, styled-components | Utility-first with tree-shaking, @tailwindcss/vite plugin for zero-config integration. No runtime CSS-in-JS overhead.                                                         |
| Routing      | React Router 7       | TanStack Router                | Proven SPA routing with lazy loading, route guards via wrapper components, and seamless integration with auth store.                                                          |
| Server Cache | TanStack React Query | SWR, manual fetch              | Built-in infinite pagination, cache deduplication, stale-while-revalidate, and query key invalidation. Clean separation from client state in Zustand.                         |
| Icons        | lucide-react         | heroicons, custom SVG          | Tree-shakeable, consistent design language. Split into separate vendor chunk to keep main bundle small.                                                                       |

### Backend: Java 21 + Spring Boot 3.3 + PostgreSQL

| Decision    | Choice                           | Alternative Considered | Reasoning                                                                                                                                                         |
| ----------- | -------------------------------- | ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Concurrency | Virtual threads                  | Reactive (WebFlux)     | Imperative code is simpler, has real stack traces, and shows comparable throughput for I/O-bound workloads. Reactive only justified with evidence of bottlenecks. |
| Persistence | Spring Data JPA + Specifications | QueryDSL, raw JPQL     | Type-safe dynamic filters with built-in pagination/sorting. Less boilerplate than alternatives.                                                                   |
| Database    | PostgreSQL 15+ only              | Multi-DB support       | Leverages PostgreSQL-specific features (trigram, recursive CTE, CITEXT, partial indexes) that generic SQL can't express.                                          |
| Auth tokens | Opaque server-stored             | JWT                    | Immediate revocation, device binding, no client-side tampering. Single-service deployment doesn't need stateless tokens.                                          |
| Migrations  | Flyway                           | Liquibase              | Simpler SQL-first approach. Repeatable and deterministic.                                                                                                         |
| Tag parsing | jaudiotagger                     | FFprobe                | Reliable multi-format parsing (MP3, FLAC, M4A, OGG). Widely used in MusicBrainz ecosystem.                                                                        |
| Transcoding | FFmpeg (external process)        | Java library           | De facto standard. Process isolation simplifies failure handling — kill on disconnect, cap concurrency, return 503 when saturated.                                |
| Caching     | Caffeine                         | Redis, Hazelcast       | In-process near-cache with short TTL. No infrastructure overhead for single-instance deployment.                                                                  |

### API Compatibility: Jellyfin Surface

The API implements the subset of Jellyfin's HTTP API that music clients use: authentication (`/Users/AuthenticateByName`), library browsing (`/Items` with filters), audio streaming (`/Audio/{id}/stream` with byte-range), playback sessions, favorites, and playlists. PascalCase parameter names maintain wire compatibility.

**Critical API contract details**:

- `Recursive: true` is mandatory for library queries — without it, only immediate children are returned (Jellyfin legacy behavior)
- Stream URLs pass `api_key` as query parameter because browsers don't send headers for `<audio src="">` requests
- Time positions use Jellyfin's 100-nanosecond "ticks" format (multiply seconds by 10,000,000)
- `maxStreamingBitrate` parameter must never be sent — it triggers HTTP 500 on some server configurations
- `Fields` parameter controls response expansion (genres, images) to minimize N+1 queries and payload size

## Security Model

**Frontend**: HTTPS enforced in production (allows localhost in dev). Session tokens stored in sessionStorage (tab-scoped, cleared on close) with optional localStorage for "Remember me." URL validation before use prevents client-side SSRF. Content Security Policy restricts script/media/connect sources. Script inline hashes prevent XSS.

**Backend**: Device-bound opaque tokens (256-bit, SecureRandom) with Caffeine-cached validation. BCrypt password hashing. One token per user per device — re-authentication revokes and replaces. Streaming paths validated against media root to prevent path traversal.

**Quality gates**: 30+ pre-commit hooks enforce formatting (Prettier, Google Java Format), linting (ESLint, SpotBugs, PMD), security scanning (gitleaks, semgrep), type coverage (>90%), and copy-paste detection. Docker build validation runs on pre-push.

## PWA and Mobile Strategy

The web app is a Progressive Web App with service worker caching: NetworkFirst for navigation (10s timeout fallback to cache), StaleWhileRevalidate for album artwork (500 entries, 30-day expiry), and NetworkOnly for audio streams (no offline playback). The manifest enables standalone display mode with dark theme.

Mobile-specific: safe area insets for notch devices, 44px minimum touch targets, momentum scrolling, bottom tab bar below `md:` breakpoint replacing the desktop sidebar. Infinite scroll with intersection observer for library pagination.

Dark mode only — intentional design choice for a music player typically used in low-light environments.

## Deployment Topology

**Development**: Docker Compose with four services — PostgreSQL, Java backend (port 8096), Vite dev server with HMR (port 5173), and optional audio separator (karaoke profile). Service health checks gate startup ordering.

**Production**: Multi-stage Docker builds produce minimal images (nginx:alpine for frontend, eclipse-temurin:21-jre-alpine for backend). Frontend entrypoint injects runtime configuration (backend URL, CSP hashes) via envsubst — same image runs on dev/staging/production. Non-root users in both containers. Nginx thread pool with async I/O and direct I/O for large files enables efficient concurrent streaming.

**GitOps**: Strategy 1 (auto-deploy from main). Push → CI builds `main-<sha7>` image → Argo CD Image Updater detects → auto-sync to `yay-tsa-production` namespace. Managed in the separate gitops repository.

## Performance Targets

| Metric                             | Target                 | Tool            |
| ---------------------------------- | ---------------------- | --------------- |
| Frontend bundle (gzipped)          | <150 KB                | bundlewatch     |
| First Contentful Paint             | <1.2s                  | Lighthouse (3G) |
| Time to Interactive                | <2.5s                  | Lighthouse      |
| Frontend memory (idle)             | <50 MB                 | Chrome DevTools |
| /Items p95 (50k tracks, filtered)  | <150 ms                | Micrometer      |
| Streaming time-to-first-byte (SSD) | <100 ms                | curl            |
| Library scan throughput            | thousands of files/min | Micrometer      |

## Additional Documentation

- **services/server/CLAUDE.md** — Backend implementation plan, data model, and endpoint behavior specification
