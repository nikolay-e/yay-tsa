> Extends [../code/CLAUDE.md](../code/CLAUDE.md) — inherits workspace conventions

# Yaytsa — Architecture Manifesto

## Product Vision

Yaytsa is a self-hosted music server with a unified stateful core, multi-protocol interfaces, and adaptive intelligence.

It is not a file browser with a play button. It is not a Subsonic clone with a fresh coat of paint. It is a **music state engine** — a system that owns the authoritative truth about what is playing, what should play next, what the user likes, and how the listening session should evolve.

The goal is to build a server that:

- stores and serves a music library from the user's own files;
- speaks multiple protocols simultaneously, giving instant access to dozens of existing clients;
- maintains a single, consistent playback state across all devices and interfaces;
- supports not just manual queue management but intelligent, LLM-driven adaptive playback;
- treats ML audio analysis and karaoke as first-class subsystems, not afterthoughts;
- remains architecturally strict, auditable, and fit for years of evolution.

---

## System Anatomy

Yaytsa is composed of three layers: **Core**, **Infrastructure**, and **Interface**. Every piece of the system belongs to exactly one of them.

### Yaytsa Core

Core is the central state engine. It does not render UI, does not scan filesystems, does not open network sockets, and does not depend on any specific client application.

Its job is to be the **single authoritative write-side** for all server state:

- what is currently playing and at what position;
- which device holds the playback lease;
- what the queue looks like and who can modify it;
- what the user's favorites, preference contract, and playlists contain;
- how the adaptive queue responds to listening signals;
- what typed failures are returned on conflict, replay, or invariant violation.

**Analogy.** The Linux kernel does not draw windows or decode audio. It manages system state and exposes an interface for userspace programs. Yaytsa Core does the same for music: it manages music state, and external clients and adapters communicate with it through protocols.

Core is further split into three tiers:

**`core-domain`** — Pure domain logic. Handlers, value objects, aggregate types, typed failures. Zero I/O, zero framework dependencies. A handler is a function: it receives data, checks invariants, returns new state or failure. Nothing else.

**`core-application`** — Orchestration. Loads the aggregate snapshot, fetches cross-context data, checks idempotency, calls the handler, saves the result, writes the outbox, closes the transaction. This layer is explicitly _not_ pure — it coordinates side effects through port interfaces.

**`infra-*`** — Everything that touches the real world. PostgreSQL, JPA entities, Flyway migrations, filesystem scanner, ML workers, karaoke pipeline, notification delivery, protocol adapters.

### Yaytsa Interface

If Core is the engine, Interface is everything through which a user or external agent interacts with that engine.

**Yaytsa PWA / Native UI** — The full product interface. All features available: playback, playlists, adaptive queue, preference contract, karaoke mode, ML-powered discovery.

**OpenSubsonic adapter** — Compatibility with the existing ecosystem of ~20 Subsonic/OpenSubsonic clients: Symfonium, DSub, Feishin, play:Sub, Sonixd, Supersonic, and others. Supports browse, playback, queue, favorites, playlists within the limits of the protocol. Does not expose adaptive queue, karaoke, or preference contract — these require the custom Yaytsa extensions (exposed through the same Jellyfin adapter).

**MPD adapter** — Compatibility with the Music Player Daemon ecosystem: ncmpcpp, mpc, Cantata, M.A.L.P. For terminal workflows and Linux-centric setups.

**MCP adapter** — Model Context Protocol integration for LLM agents. Claude or any MCP-aware agent can browse the library, manage the queue, control playback, modify the preference contract, and steer adaptive behavior through tool calls.

**Future adapters** — Any system that can translate an external protocol into Core commands is a valid interface. The architecture imposes no limit on the number of entry points.

The critical invariant: **interfaces can be many, but the source of truth is one**. There are no parallel centers of authority. One Core, as many clients as needed on top.

### Yaytsa Workers

Workers are background processes that populate read-only data stores. They are not part of Core's command surface — they bypass `core-domain` entirely and write directly to their own schemas.

**Library Scanner** (`infra-library-scanner`) — Walks the filesystem, reads audio tags, populates the library schema. Single writer, upsert semantics, no OCC.

**ML Worker** (`infra-ml-worker`) — Runs Essentia, Discogs, MusicNN, CLAP, and MERT extractors against audio files. Writes embeddings and scalar features to the ML schema.

**Karaoke Worker** (`infra-karaoke-worker`) — Runs Demucs/Spleeter for vocal/instrumental separation. Writes stems and lyrics timing to the karaoke schema.

**LLM Orchestrator** (`infra-llm`) — Connects to the LLM API, receives playback signals, generates queue edits, writes decisions to the adaptive context.

---

## Central Architectural Idea

### One write-side, many read-sides, many interfaces

Every bounded context has exactly one authoritative write path. All mutations flow through Core. No business logic lives in UI or adapters. Read models and projections are derived from the authoritative state.

This yields three properties simultaneously:

**Consistency.** The same playback state is visible in the PWA, in Symfonium, and in an MPD client. A user can start a session on the desktop, pick up a phone, open Symfonium, and see the same queue, same track, same position.

**Predictability.** Every state change follows the same path: command → use case → pure handler → persistence → outbox. There are no backdoor mutations, no triggers with hidden side effects, no silent state corruption.

**Extensibility.** Adding a new protocol or a new UI requires writing a thin adapter that translates external requests into Core commands. The adapter contains zero business logic.

### CQRS without event sourcing

Yaytsa Core uses CQRS without event sourcing. The authoritative source of truth is the **current state of each aggregate in the database**, not a log of events.

For each write context:

1. The use case loads the current aggregate snapshot.
2. The pure handler receives snapshot + command + context + deps and returns either a new snapshot or a typed failure.
3. The use case persists the new snapshot with OCC.
4. Notifications go into the outbox within the same transaction.

Event logs exist in the system — `playback_signal`, `llm_decision_log`, `play_history` — but they serve operational, analytical, and orchestration purposes. They are not the mechanism for reconstructing system state.

---

## Bounded Contexts

The system is partitioned into eight isolated contexts. Each context owns its data, its commands, its database schema, and its invariants. Cross-context communication happens only through application-layer ports.

### Write contexts

These are the subsystems where users or orchestrators actively mutate state. Each has aggregates with OCC, idempotency, and typed command/failure surfaces.

#### 1. Auth

Users, credentials, API tokens, device sessions. Aggregate: `UserAggregate`.

Responsible for identity and access. Every other context trusts Auth's `UserId` as the caller identity.

#### 2. Playback

Current playback session, queue, current entry, playback state, lease, position. Aggregate: `PlaybackSessionAggregate`.

The heart of the system. Answers the question: _what is playing right now, and who controls it?_

Position tracking follows **Variant B** (lazy computation). The aggregate stores `lastKnownPosition` and `lastKnownAt`. Current position is computed on read:

- If `PLAYING`: `lastKnownPosition + (now - lastKnownAt)`
- If `PAUSED` or `STOPPED`: `lastKnownPosition`

Writes to the database occur only on pause, seek, track change, and stop — not during continuous playback. This eliminates ~60 writes/minute per active session.

#### 3. Adaptive

Listening session, versioned adaptive queue, playback signals, LLM decision log. Aggregate: `AdaptiveQueueAggregate`.

The intelligence layer. This context manages the LLM-DJ subsystem: it receives playback signals (skip, completion, thumbs-up/down), consults the taste profile and preference contract, and atomically rewrites the tail of the queue. `queue_version` increments on each such rewrite.

Adaptive is a separate aggregate from Playback because it has a fundamentally different concurrency model. Playback answers _what is playing now_. Adaptive answers _what should play next and why_.

#### 4. Preferences

Favorites (with ordering and timestamps), preference contract (hard rules, soft preferences, DJ style, red lines). Aggregate: `UserPreferencesAggregate`.

The preference contract is the user's explicit instruction set for the LLM-DJ: "never play screamo," "prefer jazz in the evening," "transition smoothly between genres." This is a first-class data structure, not a free-text field.

#### 5. Playlists

User-curated ordered collections of tracks with metadata: description, public/private flag, per-entry `addedAt` timestamp. Aggregate: `PlaylistAggregate`.

### Read-only / worker-owned contexts

These subsystems are read by Core but not mutated by user commands. Their data is populated by dedicated background workers.

#### 6. Library

The polymorphic media library. Follows a Jellyfin-style model: a root `entities` table with an `entity_type` discriminator, extended by specialization tables (`audio_tracks`, `albums`, `artists`). Supporting tables: `genres`, `entity_genres`, `images`.

Written exclusively by the library scanner. No user-facing commands, no OCC, no aggregates. For Core, this is a read-only query surface exposed through `LibraryQueryPort`.

The polymorphism lives in persistence. For the domain, it is hidden behind a sealed interface `LibraryEntity` with typed subtypes: `Track`, `Album`, `Artist`, `Folder`.

#### 7. ML

Audio embeddings per track: Discogs (1280d), MusicNN (200d), CLAP (512d), MERT (768d). Scalar features: BPM, energy, valence, danceability, loudness, spectral complexity, and more. Per-user: taste profile (aggregated preference vector), user-track affinity (granular per-track engagement metrics).

Written by the ML worker. HNSW indexes on vector columns enable sub-linear similarity search. This is what makes adaptive queue and discovery features possible — the system operates in a real musical embedding space, not just tag-based filtering.

#### 8. Karaoke

Vocal/instrumental separation artifacts (Demucs/Spleeter), lyrics timing for synchronized display, readiness state per track.

Written by the karaoke worker pipeline. This is a first-class subsystem: the PWA can offer karaoke mode for ready tracks, and the LLM-DJ can factor "I want to sing" into its queue decisions.

### Why this decomposition matters

- **Clear ownership.** Every table, every aggregate, every command belongs to exactly one context.
- **Independent evolution.** Changing the ML pipeline does not touch playback. Adding a new preference type does not affect playlists.
- **Controlled coupling.** Cross-context dependencies are explicit — they go through ports and read-projections, not through shared mutable state.
- **Schema isolation.** Each context gets its own PostgreSQL schema (`core_v2_auth`, `core_v2_library`, `core_v2_playback`, etc.), making ownership visible at the SQL level.

---

## System Guarantees

Yaytsa is not just a collection of features. It is a system with hard behavioral guarantees. Violating any of them is a bug.

### Optimistic Concurrency Control

Every write command carries an `expectedVersion`. Persistence uses conditional update:

```sql
UPDATE ... SET version = :next WHERE version = :expected
```

If another writer modified the aggregate first, the command receives `Failure.Conflict`. This prevents silent lost updates.

### Idempotency

Every external write command carries a client-generated `idempotencyKey`. The system stores the full result of each command execution. On replay:

- Same key, same payload → returns the exact same `CommandResult` as the first execution.
- Same key, different payload → returns `Failure.InvariantViolation`.

This protects against retry storms, double-clicks, flaky networks, and client-side replay.

### Single-Writer Lease

Playback sessions are controlled through a device lease. At any moment, only one device can mutate the queue, playback state, seek position, or track navigation. The lease has a TTL; if it expires (device crash, lost connectivity), another device can acquire it.

This solves multi-device conflicts without CRDTs or operational transforms. For a personal music server, this is the right trade-off: simple, correct, sufficient.

### Pure Domain Handlers

All domain logic is deterministic and side-effect-free. Handlers are functions:

```
(snapshot, command, context, deps) → CommandResult<NewSnapshot> | Failure
```

No Spring, no JPA, no Jackson, no SLF4J, no `Instant.now()`, no I/O of any kind in `core-domain`. Testing is instantaneous — no mocks, no containers, no network.

### Typed Failures

All expected errors are part of the domain model, not exceptions:

- `Failure.Conflict` — version mismatch
- `Failure.Unauthorized` — wrong device, expired lease, wrong user
- `Failure.NotFound` — entity does not exist
- `Failure.InvariantViolation` — business rule violated
- `Failure.UnsupportedByProtocol` — command not available in this protocol
- `Failure.StorageConflict` — database-level concurrent modification

An error is a normal business result, not a crash.

### Externalized Time

No code in `core-domain` or `core-application` reads the system clock. Time enters through `CommandContext.requestTime`, provided by the adapter layer via a `Clock` interface. All time-dependent behavior (lease expiry, timestamps, position computation) is fully deterministic in tests.

### Domain-Persistence Separation

Domain classes live in `core-domain`. JPA entities live in `infra-persistence`. Mapping between them is the infrastructure layer's responsibility. The domain does not know it is stored in PostgreSQL.

### Transactional Outbox

Notifications (PlaybackStateChanged, PlaylistChanged, etc.) are written to an outbox table in the same database transaction as the aggregate state change. A separate poller reads the outbox after commit and delivers notifications through WebSocket or other channels.

If the transaction commits, the notification is guaranteed to be delivered. If the transaction rolls back, the notification never leaves the system.

---

## Cross-Context Data Flow

### The deps pattern

A handler never receives a repository, service, or port. If it needs data from another context, the use case loads that data _before_ calling the handler and passes it through a `deps` parameter.

Example: `AddToQueue` must verify that referenced tracks exist in the library. The use case queries `LibraryQueryPort.trackIdsExist(...)`, then passes the result as `PlaybackDeps(knownTrackIds = ...)`. The handler checks the set and rejects unknown tracks with a typed failure. It never knows where the data came from.

This makes cross-context dependencies:

- **Explicit** — the `deps` class documents exactly what external data each command needs.
- **Minimal** — only the required data is loaded, nothing more.
- **Testable** — in tests, deps are just constructor arguments.

### Port interfaces

`core-application` contexts communicate through port interfaces. A context can import another context's port subpackage (`core-application/Y/port/`) but never its use cases or domain types. This is enforced by ArchUnit rules.

Concretely: `PlaybackUseCases` depends on `LibraryQueryPort` to validate track existence. It does not depend on `LibraryQueries` or any `core-domain/library` type.

---

## Adaptive Queue and LLM-DJ

The defining feature of Yaytsa. Not a gimmick — a core architectural subsystem.

### How it works

1. The user starts a listening session (explicitly or implicitly on first play).
2. Playback signals flow into the adaptive context: track started, track completed, track skipped, seek-to-end, thumbs-up, thumbs-down.
3. The LLM orchestrator analyzes the signal stream against the user's taste profile, preference contract, current session context (energy, mood, attention mode), and the available music space (via ML embeddings).
4. The orchestrator produces a queue edit: a new tail of N tracks with intent labels and reasoning.
5. The edit is applied atomically by incrementing `queue_version`. The currently playing track is never interrupted.
6. The decision is logged in `llm_decision_log` with full audit trail: prompt hash, token counts, latency, applied edits, validation result.

### Why it is a separate aggregate

The adaptive queue has its own concurrency model. Playback sessions use a device lease with per-command OCC. The adaptive queue uses versioned snapshots — the LLM can prepare a new queue version in the background while the user listens to the current track, and the switch happens atomically on track transition.

These are fundamentally different write patterns that must not be conflated in a single aggregate.

---

## Module Layout

```
core-domain/
  shared/           Ids, Result, Failure, CommandContext, Command, Query
  auth/             UserAggregate, AuthHandler, AuthCommand
  library/          Track, Album, Artist, Genre, Image, SearchResults
  playback/         PlaybackSessionAggregate, PlaybackHandler, PlaybackCommand
  adaptive/         ListeningSession, AdaptiveQueueEntry, PlaybackSignal, LlmDecision
  preferences/      UserPreferencesAggregate, PreferencesHandler, PreferencesCommand
  playlists/        PlaylistAggregate, PlaylistHandler, PlaylistCommand
  ml/               TrackFeatures, TasteProfile, UserTrackAffinity
  karaoke/          KaraokeAsset

core-application/
  shared/           IdempotencyStore, Clock, OutboxPort, TransactionalCommandExecutor,
                    PayloadFingerprint, ProtocolCapabilitiesRegistry
  auth/             AuthUseCases, UserRepository port
  library/          LibraryQueries, LibraryQueryPort
  playback/         PlaybackUseCases, PlaybackSessionRepository port
  adaptive/         AdaptiveQueryPort
  preferences/      PreferencesUseCases, UserPreferencesRepository port
  playlists/        PlaylistUseCases, PlaylistRepository port
  ml/               MlQueryPort
  karaoke/          KaraokeQueryPort

infra-persistence/
  shared/           SpringTransactionalCommandExecutor, outbox table, idempotency table
  auth/             JPA entities, repositories, mappers, Flyway migrations
  library/          JPA entities, repositories, mappers, Flyway migrations
  playback/         JPA entities, repositories, mappers, Flyway migrations
  adaptive/         JPA entities, repositories, mappers, Flyway migrations
  preferences/      JPA entities, repositories, mappers, Flyway migrations
  playlists/        JPA entities, repositories, mappers, Flyway migrations
  ml/               JPA entities, repositories, mappers, Flyway migrations
  karaoke/          JPA entities, repositories, mappers, Flyway migrations

infra-library-scanner/    Filesystem walker, tag reader, library write-side
infra-ml-worker/          Audio feature extraction, embedding generation
infra-karaoke-worker/     Demucs/Spleeter pipeline, stem storage
infra-llm/                LLM API client, signal processing, queue edit generation
infra-media/              Audio transcoding, stream serving
infra-notifications/      WebSocket fan-out, outbox poller

adapter-jellyfin/         Yaytsa protocol (Jellyfin standard + custom extensions)
adapter-opensubsonic/     Subsonic/OpenSubsonic protocol translation
adapter-mcp/              MCP tool definitions for LLM agents
adapter-mpd/              MPD protocol server

core-testkit/             In-memory ports, FixedClock, DirectTransactionalExecutor
app/                      Spring Boot composition root, ArchUnit tests
```

### Dependency rules (enforced by ArchUnit)

1. `shared` depends on nothing but `kotlin-stdlib`.
2. `core-domain/X` cannot import `core-domain/Y` — only `shared`.
3. `core-application/X` can import `core-application/Y/port/` — never `Y/usecase/`.
4. Adapters may reference `core-domain` types (commands, aggregates, value objects) for protocol translation, but must never call domain Handlers directly. All business logic flows through `core-application` use cases.
5. `infra-persistence/X` can import `core-domain/X` and `core-application/X/port/`.
6. Workers bypass `core-domain` and write directly to their schemas.

---

## Technology Stack

| Layer       | Technology                                                                            |
| ----------- | ------------------------------------------------------------------------------------- |
| Language    | Kotlin (domain, application), Java (infrastructure where needed)                      |
| Runtime     | JVM 21                                                                                |
| Framework   | Spring Boot 3.4                                                                       |
| Persistence | PostgreSQL 16, Spring Data JPA, Hibernate 6                                           |
| Migrations  | Flyway (per-schema, per-context)                                                      |
| Extensions  | pgvector (HNSW similarity search), pg_trgm (fuzzy text search), citext                |
| Build       | Gradle 8.12 with Kotlin DSL, version catalog                                          |
| Testing     | Kotest (unit + property-based), Testcontainers (integration), ArchUnit (architecture) |
| Code style  | ktlint via Spotless                                                                   |
| CI          | GitHub Actions                                                                        |

---

## Migration Strategy

Yaytsa Core v2 is a full rewrite of the domain model and persistence layer, migrating from a legacy production schema. The migration follows a **big-bang** strategy:

1. Freeze the old system (read-only mode).
2. Snapshot the old database (`pg_dump`).
3. Run the ETL migration tool against the old schema, writing into the new `core_v2_*` schemas.
4. Validate: row counts, checksums, spot checks on key entities.
5. Start the new application against the new schemas.
6. Observe for one week; drop old schema once confident.

Data is classified into three preservation tiers:

- **Tier 1 — irreplaceable.** Users, API tokens, play history, track features (expensive GPU embeddings), playback signals, LLM decision logs, taste profiles, user-track affinity, preference contracts, favorites, playlists. Must be migrated with zero loss.
- **Tier 2 — reproducible with effort.** Library metadata (can be rescanned), karaoke artifacts (can be re-separated). Migrated best-effort; re-processing is fallback.
- **Tier 3 — disposable.** Active sessions, job queues, scan logs. Not migrated.

---

## Summary

Yaytsa is a self-hosted music server with a unified stateful core, multi-protocol interfaces, and adaptive intelligence.

**Yaytsa Core** is the pure domain engine that manages playback, preferences, playlists, and adaptive queue through strict invariants: OCC, idempotency, lease, outbox, and bounded contexts.

**Yaytsa Interface** is any client or protocol that translates external requests into Core commands: PWA, OpenSubsonic, MPD, MCP, and future adapters.

**Yaytsa Workers** are background processes that populate read-only data stores: library scanner, ML feature extractor, karaoke pipeline, LLM orchestrator.

The product goal is to give the user a single server that works with the existing ecosystem of music clients, remains architecturally rigorous, and is capable of not just playing music but meaningfully conducting a listening session.

Ниже — нормальная формулировка **основных принципов Yaytsa Core**, завязанная на вашу конечную цель: **multi-protocol support, extensibility, portability, maintainability**.

---

# Основные принципы Yaytsa Core

## 1. One Core, many protocols

Yaytsa Core — это **единый источник истины для состояния системы**, независимо от того, через какой протокол пришёл запрос.

Jellyfin, OpenSubsonic, MPD, MCP и будущие интеграции не имеют собственной бизнес-логики и не владеют состоянием. Они лишь переводят внешний протокол в команды ядра и обратно.

**Смысл:** один сервер, много интерфейсов, одно консистентное поведение.

---

## 2. Protocol-agnostic domain

Доменное ядро не знает ничего о REST, JSON, WebSocket, Subsonic XML, MPD-командах или MCP tool calls.

Внутри ядра есть только:

- команды,
- query-модели,
- aggregate state,
- typed failures,
- доменные инварианты.

**Смысл:** добавление нового протокола не требует переписывать доменную модель.

---

## 3. Thin adapters, zero business logic outside core

Все адаптеры должны быть максимально тонкими.

Их задача:

- принять запрос,
- провалидировать transport-level формат,
- преобразовать его в command/query,
- вызвать use case,
- сериализовать результат.

Они не принимают архитектурных решений и не содержат правил предметной области.

**Смысл:** расширяемость без размазывания логики по системе.

---

## 4. Clear separation of concerns

Система разделена на слои с жёсткими обязанностями:

- **core-domain** — бизнес-правила и инварианты;
- **core-application** — orchestration и use cases;
- **infra-\*** — persistence, messaging, workers, protocol delivery;
- **adapters** — внешние интерфейсы.

Ни один слой не должен захватывать ответственность другого.

**Смысл:** maintainability через ясные границы.

---

## 5. Bounded contexts over monolith logic

Core строится не как одна огромная доменная модель, а как набор **изолированных bounded contexts**.

Каждый контекст владеет:

- своими типами,
- своими командами,
- своими таблицами,
- своими инвариантами,
- своей эволюцией.

**Смысл:** система растёт по модулям, а не превращается в неразделимый ком.

---

## 6. Single authoritative write-side per context

Для каждого write-context существует **ровно один авторитетный write-side**.

Все изменения состояния проходят через него.
Никакие адаптеры, UI, background jobs или внешние клиенты не должны мутировать критическое состояние в обход core-команд.

**Смысл:** предсказуемость и консистентность.

---

## 7. Pure domain, impure orchestration

Доменные handlers должны быть **чистыми функциями**:

- без I/O,
- без framework dependencies,
- без доступа к БД,
- без чтения времени из системы,
- без логирования как части бизнес-решения.

Все side effects происходят в application/infrastructure слоях.

**Смысл:** portability, testability и долговечность ядра.

---

## 8. Infrastructure must be replaceable

Core не должен зависеть от конкретной технологии доставки или хранения.

PostgreSQL, JPA, Flyway, WebSocket, filesystem scanner, ML workers — это детали инфраструктуры, а не часть доменной истины.

Ядро должно быть переносимым между:

- разными transport layers,
- разными persistence implementations,
- разными deployment environments.

**Смысл:** portability как архитектурное свойство, а не маркетинговое слово.

---

## 9. Explicit contracts between contexts

Связи между контекстами происходят только через:

- ports,
- read projections,
- preloaded deps,
- domain-safe identifiers.

Никаких скрытых кросс-импортов и прямого лазания одного контекста во внутренности другого.

**Смысл:** controlled coupling вместо хаоса.

---

## 10. Consistency first on writes

Любая write-операция должна сохранять инварианты системы даже под concurrency и retry.

Поэтому ядро обязано опираться на:

- optimistic concurrency control;
- idempotency;
- typed failure model;
- deterministic command handling.

**Смысл:** Core должен быть надёжным в реальном проде, а не только “логически красивым”.

---

## 11. Stateful by design

Yaytsa Core — не stateless proxy и не тонкий CRUD API.
Это **state engine**, который управляет:

- playback session,
- queue ownership,
- playlists,
- user preferences,
- adaptive queue,
- long-running listening context.

Состояние — это не побочный эффект, а центральный объект архитектуры.

**Смысл:** именно это делает возможным multi-device consistency и adaptive behaviour.

---

## 12. Read models may evolve independently

Чтение и отображение данных могут оптимизироваться отдельно от write-side.

Разрешены:

- projections,
- denormalized read models,
- search indexes,
- vector indexes,
- worker-generated collections.

Но они не должны подменять собой write truth.

**Смысл:** scalability и flexibility без потери архитектурной дисциплины.

---

## 13. Feature growth must preserve the core constitution

Новые возможности — adaptive queue, karaoke, ML, новые протоколы, новые клиенты — добавляются не ценой разрушения ядра, а через расширение в рамках тех же правил.

Фича допустима только тогда, когда:

- не ломает границы слоёв;
- не тащит бизнес-логику в адаптеры;
- не создаёт второй источник истины;
- не ухудшает testability и replaceability.

**Смысл:** расширяемость без деградации архитектуры.

---

## 14. Maintainability over local convenience

Локально удобные, но архитектурно грязные решения считаются дефектом, даже если они “быстро работают”.

Запрещено жертвовать:

- модульностью,
- читаемостью,
- явностью зависимостей,
- предсказуемостью поведения

ради короткосрочной скорости разработки.

**Смысл:** Core строится на годы, а не на один релиз.

---

## 15. The product goal drives the architecture

Архитектура Yaytsa Core подчинена трём стратегическим целям:

- **many protocols** — одно ядро должно обслуживать разные клиентские миры;
- **extensibility** — новые контексты, фичи и адаптеры должны добавляться без переписывания основы;
- **portability and maintainability** — ядро должно переживать смену инфраструктуры, интерфейсов и команд разработки.

Это не вторичные качества. Это и есть критерии правильности архитектуры.

---

# Короткая версия

Можно свести всё к такой компактной формуле:

> **Yaytsa Core — это protocol-agnostic, stateful, extensible domain kernel для музыкального сервера.**
>
> Он обеспечивает один источник истины для состояния системы, поддерживает множество протоколов через тонкие адаптеры, изолирует бизнес-логику от инфраструктуры и сохраняет переносимость, расширяемость и сопровождаемость как главные архитектурные инварианты.

---

# Ещё более жёсткая версия в виде тезисов

1. **One core, many interfaces.**
2. **Domain first, protocol second.**
3. **State is authoritative.**
4. **Adapters are thin.**
5. **Contexts are isolated.**
6. **Infrastructure is replaceable.**
7. **Writes are consistent and deterministic.**
8. **Purity in domain, side effects at the edge.**
9. **Extensibility must not erode architecture.**
10. **Portability and maintainability are first-class requirements.**

Могу сразу оформить это как раздел для ADR/README под заголовком `Core Principles`.
