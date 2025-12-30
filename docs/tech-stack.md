# Future Technology Stack (AI Vibe Coding Optimized)

This document outlines the target technology stack for potential future migration. The stack prioritizes reliability, predictability, ease of self-hosting, and **AI-assisted development efficiency**.

## Architecture Principles

- **Conservative choices**: Battle-tested technologies with predictable behavior
- **Self-host friendly**: Minimal external dependencies, simple operations
- **Type safety**: Compile-time checks where possible
- **Observable**: Built-in metrics, logging, and tracing
- **AI Vibe Coding optimized**: Technologies with maximum LLM training data coverage

## Component Overview

| Component                     | Technologies                                                | Purpose / Notes                                                                                      |
| ----------------------------- | ----------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| **Reverse Proxy + TLS**       | Nginx                                                       | TLS termination, gzip/brotli compression, rate limiting, static file caching, efficient file serving |
| **Private Media Serving**     | Nginx `X-Accel-Redirect` + sendfile + HTTP Range            | Java handles authorization only, Nginx streams bytes and handles seeking                             |
| **Backend API**               | Java 21 (LTS) + Spring Boot 3 + Spring MVC                  | Classic synchronous model, predictable debugging                                                     |
| **Authentication**            | Spring Security (OAuth2 Resource Server)                    | custom JWT/login through Spring Security                                                             |
| **API Contract**              | OpenAPI: `springdoc-openapi` + `openapi-generator`          | Spec generated from controllers, frontend client auto-generated                                      |
| **Database**                  | PostgreSQL                                                  | ACID compliance, strict schemas, constraints, reliability                                            |
| **Migrations**                | Flyway                                                      | Transparent migrations, simple operations                                                            |
| **SQL Layer**                 | jOOQ                                                        | Type-safe SQL, query control (no ORM magic)                                                          |
| **Search (Full)**             | Elasticsearch _(or OpenSearch)_                             | Full-text search, autocomplete, typo tolerance, facets (when truly needed)                           |
| **Search (Lightweight)**      | PostgreSQL FTS + `pg_trgm`                                  | Often sufficient without a separate search cluster                                                   |
| **Task Queue**                | RabbitMQ                                                    | Reliable queue, ack/retry, load control                                                              |
| **Queue Alternative**         | PostgreSQL queue via `SKIP LOCKED` _(+ JobRunr/Quartz)_     | Less infrastructure, often sufficient for self-host                                                  |
| **Worker (Heavy Tasks)**      | Spring Boot worker (separate application)                   | Scanning, indexing, transcoding, loudness analysis, recommendation recalculation                     |
| **Transcoding**               | FFmpeg (ProcessBuilder)                                     | AAC/Opus encoding, HLS packaging, metadata/artwork extraction                                        |
| **Adaptive Streaming**        | HLS (audio-only)                                            | Convenient for unstable networks, TV, mobile devices                                                 |
| **Source Storage**            | Regular filesystem / NAS (bind mount)                       | Simplest and most reliable option for self-host, backed up with rsync                                |
| **Derived Storage (Cache)**   | Filesystem (`/var/lib/app/cache/...`)                       | HLS segments, previews, waveforms, temporary results                                                 |
| **Loudness Normalization**    | FFmpeg loudness scan + LUFS/ReplayGain stored in PostgreSQL | Consistent volume levels across tracks without manual adjustment                                     |
| **Karaoke/Lyrics**            | LRC format + versions stored in PostgreSQL                  | Line timestamps, editor support, versioning                                                          |
| **Recommendations (MVP)**     | Co-occurrence / item-item + rules                           | "Radio from track", "similar", "continue listening" — cheap and useful                               |
| **Recommendations (Classic)** | ALSO (implicit feedback) offline (Python worker or Java)    | Nightly/periodic recalculation, results stored in PostgreSQL                                         |
| **Frontend**                  | React 18 + TypeScript + Tailwind CSS                        | Maximum LLM training data coverage, optimal for AI-assisted development                              |
| **UI Kit**                    | shadcn/ui + Radix UI primitives                             | Copy-paste components (not abstracted library), full code visibility for LLM                         |
| **Frontend State**            | Zustand                                                     | Minimal boilerplate, single-file stores, easy for LLM to generate                                    |
| **Frontend Build**            | Vite                                                        | Fast HMR, simple configuration, excellent TypeScript support                                         |
| **Frontend Routing**          | React Router v6                                             | Well-documented, large training data presence                                                        |
| **API Client**                | TanStack Query (React Query) + openapi-generator            | Type-safe API calls, caching, auto-generated from OpenAPI spec                                       |
| **Web Player**                | HTML5 Audio API + Media Session API                         | Play/pause from headset/OS, proper UX                                                                |
| **Audio State**               | Zustand (dedicated audio store)                             | Singleton audio engine pattern, React components only render state                                   |
| **HLS in Browser**            | hls.js                                                      | For browsers without native HLS support                                                              |
| **Observability**             | OpenTelemetry + Prometheus + Grafana + Loki                 | Visibility into "what's on fire" and why                                                             |
| **Build/Dependencies**        | Maven                                                       | Conservative, universally supported                                                                  |
| **Containerization/Deploy**   | Docker + Docker Compose                                     | Self-host without Kubernetes                                                                         |
| **Testing (Backend)**         | JUnit 5 + Testcontainers + WireMock                         | Integration tests closely matching production conditions                                             |
| **Testing (Frontend)**        | Vitest + Testing Library                                    | Fast, Vite-native, well-supported by LLMs                                                            |
| **Frontend Static Files**     | Nginx                                                       | Serves SPA, caching, low resource usage                                                              |

## Why This Frontend Stack for AI Vibe Coding

| Technology   | AI Advantage                                                        |
| ------------ | ------------------------------------------------------------------- |
| React        | Dominant market share = largest training data corpus                |
| TypeScript   | Types provide context, LLM generates more accurate code             |
| Tailwind CSS | Utility classes inline, no separate CSS files to synchronize        |
| shadcn/ui    | Components copied into project, LLM can read and modify actual code |
| Zustand      | ~10 lines for a complete store vs 50+ for Redux/NgRx                |
| Vite         | Minimal config, LLM rarely makes mistakes                           |
| React Query  | Declarative data fetching, less boilerplate than manual useEffect   |

## Audio Player Architecture

```
┌─────────────────────────────────────────────────────────┐
│                      React UI Layer                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐  │
│  │ PlayerBar   │  │ QueuePanel  │  │ TrackProgress   │  │
│  └──────┬──────┘  └──────┬──────┘  └────────┬────────┘  │
│         │                │                   │           │
│         └────────────────┼───────────────────┘           │
│                          ▼                               │
│              ┌───────────────────────┐                   │
│              │   usePlayerStore()    │ ◄── Zustand       │
│              │   (React hooks)       │                   │
│              └───────────┬───────────┘                   │
└──────────────────────────┼──────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│                 Audio Engine (Pure TypeScript)            │
│  ┌────────────────────────────────────────────────────┐  │
│  │  class AudioEngine {                               │  │
│  │    private audio: HTMLAudioElement                 │  │
│  │    private hls?: Hls                               │  │
│  │    // Singleton, lives outside React lifecycle     │  │
│  │  }                                                 │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

Key principle: Audio engine is a singleton class in pure TypeScript. React components subscribe to Zustand store and dispatch actions. This prevents re-render issues and lifecycle problems.

## Migration Phases

### Phase 1: MVP

- PostgreSQL + Flyway + jOOQ
- Spring Boot 3 + Spring MVC + Spring Security
- Nginx reverse proxy with X-Accel-Redirect
- FFmpeg for transcoding
- Filesystem storage
- React + TypeScript + Tailwind + Vite + Zustand
- Docker Compose deployment

### Phase 2: Enhanced Features

- PostgreSQL FTS + pg_trgm for search
- PostgreSQL queue with SKIP LOCKED
- Loudness normalization
- Basic recommendations (co-occurrence)
- OpenTelemetry integration
- shadcn/ui component library integration
- TanStack Query for API layer

### Phase 3: Scale & Polish

- RabbitMQ (if queue needs exceed PostgreSQL)
- Elasticsearch (if search needs exceed PostgreSQL FTS)
- HLS adaptive streaming
- ALS-based recommendations
- Keycloak SSO (if multi-app ecosystem)
- Waveform visualization (Web Audio API + Canvas)
- Offline support (IndexedDB + Service Worker)

## Stack

| Aspect        | Stack (AI-Optimized)               |
| ------------- | ---------------------------------- |
| Frontend      | React + TypeScript + Tailwind CSS  |
| UI Kit        | shadcn/ui + Radix                  |
| State         | Zustand                            |
| Build         | Vite                               |
| API Layer     | TanStack Query + openapi-generator |
| Backend       | Java Spring Boot (enhanced)        |
| Database      | PostgreSQL + jOOQ                  |
| Search        | PostgreSQL FTS → Elasticsearch     |
| Media Serving | Nginx X-Accel-Redirect             |
| Streaming     | HLS adaptive                       |

## Project Initialization

```bash
# Frontend
npm create vite@latest frontend -- --template react-ts
cd frontend
npm install -D tailwindcss postcss autoprefixer
npx tailwindcss init -p
npm install zustand @tanstack/react-query react-router-dom
npx shadcn@latest init

# Backend (existing Spring Boot setup)
# ...
```

## Notes

- This stack trades some developer convenience for operational predictability
- jOOQ over JPA/Hibernate for explicit SQL control
- React over Angular/Svelte for maximum AI coding assistance quality
- shadcn/ui over Material UI for code transparency (LLM can modify components)
- Maven over Gradle for stability and tooling support
- Filesystem over object storage for self-host simplicity
- Zustand over Redux/NgRx for minimal boilerplate (critical for AI generation accuracy)
