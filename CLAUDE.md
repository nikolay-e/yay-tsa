# CLAUDE.md

> Extends [../CLAUDE.md](../CLAUDE.md) - inherits workspace conventions (integration tests only, no docstrings, Russian communication)

## Project Overview

Yay-Tsa is a self-hosted music streaming system: a multi-protocol Kotlin server (`yay-tsa-v2/`, Spring Boot 3.4 / JDK 21, hexagonal architecture with eight bounded contexts) paired with a React PWA client (`apps/web/`). The old Java Spring Boot v1 backend was retired on 2026-05-16; its code, chart templates, and CI jobs have been removed. The v2 backend speaks four protocols simultaneously — Jellyfin (custom Yaytsa extensions), OpenSubsonic, MPD, and MCP — over a single authoritative state engine.

The system is designed around two hard problems: **a single, consistent music state visible across every protocol and device**, and **correct, efficient audio streaming with seek support**.

## Git Workflow

**We always work on `main`.** Commit and push directly to `main` (fast-forward, no PRs) — this is a solo project. CI (GitHub Actions: `CI` for the PWA, `CI (v2)` for the backend) gates every push.

**Stale branches:** the repo carries many unmerged branches (old `feature/*`, `fix/*`, `claude/*`, and contributor branches). At the start of meaningful work, review the unmerged branches (`git branch -r --no-merged main`), determine whether any hold work that still belongs in `main`, merge what's needed, and delete what's obsolete so the branch list reflects reality. Never assume an unmerged branch is dead without checking its diff against `main`.

## Common Commands

```bash
# Frontend (PWA)
npm run dev                    # Build core+platform, start Vite dev server with HMR
npm run build                  # Production build (core → platform → web)
npm run type-check             # TypeScript type checking
npm run lint                   # Prettier check
npm run format                 # Prettier write
npm run pre-commit             # Run all pre-commit hooks

# Backend (v2 Kotlin)
cd yay-tsa-v2 && ./gradlew build        # Compile + test all modules
cd yay-tsa-v2 && ./gradlew :app:bootRun  # Run locally against shared-postgres
cd yay-tsa-v2 && ./gradlew spotlessApply # Format Kotlin

# Testing
cd packages/core && npm run test:integration    # PWA-core integration tests against a live server
cd apps/web && npm run test:e2e                 # Playwright E2E (chromium + mobile + webkit)
cd yay-tsa-v2 && ./gradlew test                 # Backend integration tests (Testcontainers)

# Kubernetes (via GitOps)
# Helm charts in this repo: charts/yay-tsa (PWA + audio-separator)
#                           charts/yay-tsa-v2 (backend)
# Values + ArgoCD apps in the gitops repo (separate); ArgoCD Image Updater auto-bumps tags.
```

## Architecture: Three-Layer Portability Model (Frontend)

The frontend is split into three npm packages with **strict unidirectional dependency flow**: Core → Platform → Web. This is not organizational convenience — it's a deliberate portability strategy.

### Layer 1: Core — Zero-Dependency Business Logic

The core package has **zero production dependencies** (only TypeScript and test tooling as dev deps). It contains all business logic that is inherently platform-agnostic: the HTTP client with Jellyfin-style `api_key`/Bearer auth, service abstractions for library queries and playback reporting, a PlaybackQueue state machine with shuffle/repeat logic, and all shared TypeScript types.

**Key design decisions in core**:

- **State machine over event emitter**: PlaybackQueue is a pure state machine — `next()` returns the new item deterministically, it doesn't emit events. This makes it testable without UI, predictable across platforms, and free of subscription accumulation bugs.
- **Constructor dependency injection**: All services receive the API client via constructor. This keeps services decoupled from client lifecycle and makes the dependency graph visible in type signatures.
- **Exponential backoff with full jitter**: The HTTP client implements AWS-recommended retry strategy — selective retries only for idempotent operations on network/gateway errors. Non-idempotent operations fail fast.
- **Strict error hierarchy**: Typed error classes (AuthenticationError, NetworkError) inheriting from a base MediaServerError enable precise error handling at each layer without string matching.

### Layer 2: Platform — Interface-Driven Adapters

The platform package defines **contracts** (TypeScript interfaces) for platform-specific capabilities and provides implementations for each target. Currently only web (HTML5 Audio, Media Session API, Web Audio) is implemented.

**Non-obvious implementation details**:

- **Promise chain serialization**: The HTML5 audio engine serializes load/play operations through a promise chain. Browsers can cancel play() during load(), or queue multiple play() calls — the chain ensures operations happen in order and stale loads are discarded via symbol-based cancellation tokens.
- **Dual audio elements**: Primary element for active playback + secondary for preloading enables seamless crossfade transitions and karaoke mode switching without audible gaps.
- **Error sanitization**: API keys and tokens are redacted from error messages before logging, preventing credential leaks via error tracking.

### Layer 3: Web — Thin Reactive Composition

The React layer is deliberately thin — it composes core services and platform adapters via Zustand stores, handles routing, and renders UI. Almost no business logic lives here.

**Three-tier state architecture**:

- **Server state** (TanStack React Query): Library items, album details, search results — cached, deduplicated, with infinite pagination.
- **Client state** (Zustand): Authentication, playback, UI preferences — atomic stores with selective subscriptions.
- **High-frequency state** (separate timing store): Current playback position updates at ~60fps via a micro-store with requestAnimationFrame batching to prevent re-rendering the whole player every frame.

## Architecture: Yay-Tsa v2 Kotlin Backend (`yay-tsa-v2/`)

The backend is a Kotlin hexagonal monolith composed of **eight bounded contexts** (auth, library, playback, adaptive, preferences, playlists, ml, karaoke). Each context owns its PostgreSQL schema (`core_v2_*`), its aggregates, and a single authoritative write-side. See [`yay-tsa-v2/CLAUDE.md`](yay-tsa-v2/CLAUDE.md) for the full architecture manifesto.

**One core, many protocols.** Adapters translate external protocols into core commands without business logic:

- **`adapter-jellyfin`** — Yaytsa protocol (Jellyfin-compatible plus custom `/v1/sessions/*`, `/v1/users/*/preferences`, `/v1/me/devices/*` extensions). What the React PWA talks to.
- **`adapter-opensubsonic`** — Subsonic/OpenSubsonic for clients like Symfonium, Finamp, Feishin.
- **`adapter-mpd`** — Music Player Daemon protocol for ncmpcpp/mpc/Cantata.
- **`adapter-mcp`** — Model Context Protocol tool surface for LLM agents (browse library, control playback, edit playlists/preferences).

**System guarantees enforced by the domain layer**: optimistic concurrency control (every aggregate carries a version), client-driven idempotency keys, single-writer device leases for playback sessions, pure functional handlers (no I/O), typed `Failure` results instead of exceptions, externalized time via `CommandContext.requestTime`, and a transactional outbox for notifications.

**Workers populate read-only schemas**: `infra-library-scanner` walks the filesystem and writes the `library` schema; `infra-ml-worker` computes Discogs/MusicNN/CLAP/MERT embeddings into `ml` (HNSW indexes for similarity); `infra-karaoke-worker` runs Demucs for vocal/instrumental separation; `infra-llm` orchestrates the adaptive queue via the in-cluster LiteLLM OpenAI-compatible gateway (model GPT-5.4 Mini), gated on `LLM_ENABLED` with a graceful ML-only fallback.

**Database**: shared CNPG cluster (`shared-postgres` in `shared-database` namespace), single `yaytsa_production` DB with per-context schemas. pgvector for embeddings; pg_trgm + CITEXT for search.

## Karaoke: Optional Audio Separation

Vocal-instrumental separation is provided by an optional sidecar service running BS-Roformer (default) or Hybrid Demucs via Python FastAPI (`services/audio-ml/`). The v2 backend coordinates processing, stores stems on a shared volume, and serves instrumental/vocal tracks through the standard streaming API. v2 also has its own in-process Demucs path (disabled in production via `DEMUCS_COMMAND=unsupported` — production uses the GPU sidecar).

## Technology Stack

- **Frontend**: React 19, Zustand, TanStack Query, Tailwind CSS 4, React Router 7, Vite
- **Backend (v2)**: Kotlin 2.1, JDK 21, Spring Boot 3.4 (kotlin DSL Gradle), Spring Data JPA + Hibernate 6, PostgreSQL 17 (pgvector, pg_trgm, CITEXT, pgcrypto), Flyway (per-context schemas)
- **Auth**: Opaque device-bound tokens (256-bit SecureRandom, SHA-256 at rest, Caffeine-cached validation)
- **ML/Audio**: BS-Roformer / Demucs / Spleeter (karaoke), MERT / CLAP / Discogs / MusicNN embeddings, GPT-5.4 Mini via in-cluster LiteLLM gateway (adaptive queue / DJ, gated on `LLM_ENABLED`)

### API: Jellyfin-Compatible Plus Yaytsa Extensions

Standard Jellyfin paths the PWA uses: `/Users/AuthenticateByName`, `/Sessions/Logout`, `/Items`, `/Items/{id}/Images/{type}`, `/Audio/{id}/stream`, `/Playlists`, `/UserFavoriteItems/{itemId}`, `/Sessions/Playing[/Progress|/Stopped]`, `/Admin/Users`.

Yaytsa extensions (`/v1/*`) handle device sync (`/v1/me/devices/*`), adaptive sessions (`/v1/sessions/*`), preference contracts (`/v1/users/{id}/preferences`), and karaoke (`/Karaoke/{trackId}/{status|instrumental|vocals}`).

**Wire conventions**: PascalCase request/response keys for Jellyfin compatibility (camelCase aliases also accepted). `Recursive: true` on `/Items` traverses the polymorphic library hierarchy. Stream URLs pass `api_key` as a query parameter (browsers don't send headers for `<audio src="">`). Time positions use Jellyfin's 100-nanosecond "ticks" (`durationMs * 10_000`).

## Security Model

**Frontend**: HTTPS enforced in production. Session tokens persist in localStorage by default (survives reload, frontend version updates, and reopening an installed PWA); unchecking "Remember me" downgrades to tab-scoped sessionStorage. On startup the persisted token is re-validated against `/Users/Me` — a confirmed 401 clears auth and routes to login, while transient network/5xx errors keep the user signed in (no auto-logout on backend restart). Content Security Policy restricts script/media/connect sources; inline script hashes prevent XSS.

**Backend (v2)**: Device-bound opaque tokens (256-bit, SecureRandom, SHA-256 at rest, hashed cache key). BCrypt password hashing (strength 13). RFC 7807 Problem Details for all error responses via `@RestControllerAdvice`. Streaming paths validated against the configured library root with TOCTOU mitigation (NOFOLLOW_LINKS).

**Quality gates**: Pre-commit hooks enforce formatting (Prettier, ktlint via Spotless), linting (ESLint, semgrep p/javascript+typescript+react), secrets (gitleaks), type coverage (>90%), copy-paste detection (jscpd). v2-ci runs `./gradlew build` plus a multi-arch Docker build/push.

## PWA and Mobile Strategy

The web app is a Progressive Web App with service worker caching: NetworkFirst for navigation (10s timeout fallback to cache) and StaleWhileRevalidate for album artwork (500 entries, 30-day expiry). Audio streams are served by a custom `audio-offline-sw.js` worker — IndexedDB-with-Range when a track has been downloaded, network otherwise — so the PWA supports offline playback of downloaded tracks (download UI in `apps/web/src/features/offline/*`, auto-download favorites + listening-cache on by default, LRU eviction). The manifest enables standalone display mode with dark theme.

Mobile-specific: safe area insets for notch devices, 44px minimum touch targets, momentum scrolling, bottom tab bar below `md:` breakpoint replacing the desktop sidecar. Infinite scroll with intersection observer for library pagination.

Dark mode only — intentional design choice for a music player typically used in low-light environments.

## Deployment Topology

**Production**: Self-hosted K3s cluster.

- `yay-tsa-production` namespace (`charts/yay-tsa`): PWA frontend (nginx), audio-separator sidecar.
- `yay-tsa-v2-production` namespace (`charts/yay-tsa-v2`): Kotlin backend; serves `/api` on `yay-tsa.com` via Traefik IngressClass + strip-prefix middleware. ServiceMonitor scrapes `/manage/prometheus`.
- `shared-database` namespace: CNPG `shared-postgres` cluster with daily Barman backups to S3 (Minio). Schema list: `core_v2_auth/library/playback/adaptive/preferences/playlists/ml/karaoke/shared`. The `public` schema holds only shared extensions (pgvector, pg_trgm, citext, uuid-ossp).

**Production image flow**: Push to main → CI builds `main-<sha7>` image for the changed component → Argo CD Image Updater detects → writes back to gitops `values.images.yaml` → ArgoCD syncs.

> **CI for THIS repo = GitHub Actions (overrides the workspace `../CLAUDE.md` Forgejo note).** `origin` is `github.com:nikolay-e/yay-tsa`; the live pipelines are `.github/workflows/ci.yml` (PWA) and `v2-ci.yml` (backend), both triggered `on: push: branches: [main]`. The `gh` CLI works here — verify a push with `gh run list --branch main` / `gh run watch <id>`. The workspace-level "migrated to Forgejo / no gh CLI" note has not taken effect for yay-tsa.

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

- **`yay-tsa-v2/CLAUDE.md`** — Backend architecture manifesto: bounded contexts, OCC + idempotency + lease invariants, multi-protocol design.
