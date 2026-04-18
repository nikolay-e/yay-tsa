# ADR 0007 - Bounded Contexts and Module Layout

**Status:** Accepted

## Context

The system covers several distinct functional areas -- authentication, media library, playback, adaptive queue intelligence, user preferences, playlists, ML feature storage, and karaoke asset management. Without explicit boundaries these concerns bleed into each other, creating circular dependencies and making it impossible to reason about data ownership or deploy modules independently.

## Decision

We define eight bounded contexts, each owning its data and command surface:

| # | Context | Style | Scope |
|---|---------|-------|-------|
| 1 | `auth` | Write CQRS | Users, credentials, API tokens |
| 2 | `library` | Read-only collection | Polymorphic entities (Track / Album / Artist / Folder), genres, images. Written only by scanner. |
| 3 | `playback` | Write CQRS | PlaybackSessionAggregate with lease, queue, last-known-position |
| 4 | `adaptive` | Write CQRS | AdaptiveQueueAggregate, ListeningSession, intent labels, signals |
| 5 | `preferences` | Write CQRS | Favorites, preference contract (hard rules, soft prefs, DJ style, red lines) |
| 6 | `playlists` | Write CQRS | Ordered track list, metadata, public flag |
| 7 | `ml` | Read-only collection | track_features (embeddings), taste_profile, user_track_affinity. Written by ML worker. |
| 8 | `karaoke` | Read-only collection | karaoke_assets. Written by Demucs / Spleeter worker. |

### Module layout

Each context is a subpackage inside the corresponding Gradle module:

- `core-domain/{shared,auth,library,playback,adaptive,preferences,playlists,ml,karaoke}`
- `core-application/{shared,auth,library,playback,adaptive,preferences,playlists,ml,karaoke}`
- `infra-persistence/{auth,library,playback,adaptive,preferences,playlists,ml,karaoke}`

Worker modules live at the top level: `infra-library-scanner`, `infra-ml-worker`, `infra-karaoke-worker`, `infra-llm`.

### Dependency rules

- `shared` depends on nothing but `kotlin-stdlib`.
- `core-domain/X` cannot import `core-domain/Y` (only `shared`).
- `core-application/X` can import `core-application/Y`'s `port/` subpackage only.
- Adapters depend only on `core-application`, never `core-domain`.
- `infra-persistence/X` can import `core-domain/X` and `core-application/X/port`.

### Database schema isolation

Each context gets its own PostgreSQL schema: `core_v2_auth`, `core_v2_library`, `core_v2_playback`, `core_v2_adaptive`, `core_v2_preferences`, `core_v2_playlists`, `core_v2_ml`, `core_v2_karaoke`.

## Consequences

- Clear ownership: every table, aggregate, and command belongs to exactly one context.
- Cross-context communication happens only through application-layer ports, keeping domain models decoupled.
- Independent schema migration per context reduces deployment risk.
- The read-only collection contexts (`library`, `ml`, `karaoke`) have no user-facing write path; their data is populated by dedicated workers.
- Enforcement of dependency rules requires ArchUnit tests or Gradle module boundaries (to be configured separately).
