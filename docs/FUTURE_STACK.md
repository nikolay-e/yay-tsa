# Future Technology Stack

This document outlines the target technology stack for potential future migration. The stack prioritizes reliability, predictability, and ease of self-hosting over cutting-edge solutions.

## Architecture Principles

- **Conservative choices**: Battle-tested technologies with predictable behavior
- **Self-host friendly**: Minimal external dependencies, simple operations
- **Type safety**: Compile-time checks where possible
- **Observable**: Built-in metrics, logging, and tracing

## Component Overview

| Component                     | Technologies                                                   | Purpose / Notes                                                                                      |
| ----------------------------- | -------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| **Reverse Proxy + TLS**       | Nginx                                                          | TLS termination, gzip/brotli compression, rate limiting, static file caching, efficient file serving |
| **Private Media Serving**     | Nginx `X-Accel-Redirect` + sendfile + HTTP Range               | Java handles authorization only, Nginx streams bytes and handles seeking                             |
| **Backend API**               | Java 21 (LTS) + Spring Boot 3 + Spring MVC                     | Classic synchronous model, predictable debugging                                                     |
| **Authentication**            | Spring Security (OAuth2 Resource Server) + Keycloak (optional) | Keycloak for SSO scenarios; otherwise custom JWT/login through Spring Security                       |
| **API Contract**              | OpenAPI: `springdoc-openapi` + `openapi-generator`             | Spec generated from controllers, frontend client auto-generated                                      |
| **Database**                  | PostgreSQL                                                     | ACID compliance, strict schemas, constraints, reliability                                            |
| **Migrations**                | Flyway                                                         | Transparent migrations, simple operations                                                            |
| **SQL Layer**                 | jOOQ                                                           | Type-safe SQL, query control (no ORM magic)                                                          |
| **Search (Full)**             | Elasticsearch _(or OpenSearch)_                                | Full-text search, autocomplete, typo tolerance, facets (when truly needed)                           |
| **Search (Lightweight)**      | PostgreSQL FTS + `pg_trgm`                                     | Often sufficient without a separate search cluster                                                   |
| **Task Queue**                | RabbitMQ                                                       | Reliable queue, ack/retry, load control                                                              |
| **Queue Alternative**         | PostgreSQL queue via `SKIP LOCKED` _(+ JobRunr/Quartz)_        | Less infrastructure, often sufficient for self-host                                                  |
| **Worker (Heavy Tasks)**      | Spring Boot worker (separate application)                      | Scanning, indexing, transcoding, loudness analysis, recommendation recalculation                     |
| **Transcoding**               | FFmpeg (ProcessBuilder)                                        | AAC/Opus encoding, HLS packaging, metadata/artwork extraction                                        |
| **Adaptive Streaming**        | HLS (audio-only)                                               | Convenient for unstable networks, TV, mobile devices                                                 |
| **Source Storage**            | Regular filesystem / NAS (bind mount)                          | Simplest and most reliable option for self-host, backed up with rsync                                |
| **Derived Storage (Cache)**   | Filesystem (`/var/lib/app/cache/...`)                          | HLS segments, previews, waveforms, temporary results                                                 |
| **Loudness Normalization**    | FFmpeg loudness scan + LUFS/ReplayGain stored in PostgreSQL    | Consistent volume levels across tracks without manual adjustment                                     |
| **Karaoke/Lyrics**            | LRC format + versions stored in PostgreSQL                     | Line timestamps, editor support, versioning                                                          |
| **Recommendations (MVP)**     | Co-occurrence / item-item + rules                              | "Radio from track", "similar", "continue listening" — cheap and useful                               |
| **Recommendations (Classic)** | ALSO (implicit feedback) offline (Python worker or Java)       | Nightly/periodic recalculation, results stored in PostgreSQL                                         |
| **Frontend**                  | Angular + TypeScript + RxJS                                    | Strict architecture, DI, consistent style, suitable for SPA player                                   |
| **UI Kit**                    | Angular Material (or PrimeNG)                                  | Conservative, predictable, fast development                                                          |
| **Frontend State**            | NgRx _(or NGXS)_                                               | Strict state discipline (useful for complex UI)                                                      |
| **Web Player**                | HTML5 Audio + Media Session API                                | Play/pause from headset/OS, proper UX                                                                |
| **HLS in Browser**            | hls.js (if needed)                                             | For browsers without native HLS support                                                              |
| **Observability**             | OpenTelemetry + Prometheus + Grafana + Loki                    | Visibility into "what's on fire" and why                                                             |
| **Build/Dependencies**        | Maven                                                          | Conservative, universally supported                                                                  |
| **Containerization/Deploy**   | Docker + Docker Compose                                        | Self-host without Kubernetes                                                                         |
| **Testing**                   | JUnit 5 + Testcontainers + WireMock                            | Integration tests closely matching production conditions                                             |
| **Frontend Static Files**     | Nginx                                                          | Serves SPA, caching, low resource usage                                                              |

## Migration Phases

### Phase 1: MVP

- PostgreSQL + Flyway + jOOQ
- Spring Boot 3 + Spring MVC + Spring Security
- Nginx reverse proxy with X-Accel-Redirect
- FFmpeg for transcoding
- Filesystem storage
- Docker Compose deployment

### Phase 2: Enhanced Features

- PostgreSQL FTS + pg_trgm for search
- PostgreSQL queue with SKIP LOCKED
- Loudness normalization
- Basic recommendations (co-occurrence)
- OpenTelemetry integration

### Phase 3: Scale & Polish

- RabbitMQ (if queue needs exceed PostgreSQL)
- Elasticsearch (if search needs exceed PostgreSQL FTS)
- HLS adaptive streaming
- ALS-based recommendations
- Keycloak SSO (if multi-app ecosystem)

## Current Stack Comparison

| Aspect        | Current                | Future                         |
| ------------- | ---------------------- | ------------------------------ |
| Frontend      | SvelteKit + TypeScript | Angular + TypeScript + RxJS    |
| State         | Svelte stores          | NgRx                           |
| Backend       | Java Spring Boot       | Java Spring Boot (enhanced)    |
| Database      | PostgreSQL             | PostgreSQL + jOOQ              |
| Search        | Basic                  | PostgreSQL FTS → Elasticsearch |
| Media Serving | Direct                 | Nginx X-Accel-Redirect         |
| Streaming     | Direct files           | HLS adaptive                   |

## Notes

- This stack trades some developer convenience for operational predictability
- jOOQ over JPA/Hibernate for explicit SQL control
- Angular over React/Svelte for stricter architecture enforcement
- Maven over Gradle for stability and tooling support
- Filesystem over object storage for self-host simplicity
