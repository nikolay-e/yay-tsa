# yaytsa-server

> Extends [../../CLAUDE.md](../../CLAUDE.md) - inherits workspace conventions (integration tests only, no docstrings, Russian communication)

Java-based Jellyfin-compatible media server implementation.

## Design Overview

A single Java 21 Spring Boot modular monolith with a ports/adapters design, PostgreSQL-backed metadata, filesystem scanning via jaudiotagger, and HTTP byte-range streaming that complies with RFC 9110 solves the two core hard problems: fast, flexible queries over large libraries and correct, efficient streaming with seek support. Use opaque, revocable device-bound tokens and Java 21 virtual threads to keep the code simple while scaling blocking I/O.

Expected folder structure
server/

- pom.xml
- README.md
- openapi/media-server.yaml
- docker/
  - Dockerfile
  - docker-compose.yml
- src/main/resources/
  - application.yml
  - db/migration/V1\_\_init.sql
- src/main/java/com/yaytsa/server/
  - MediaServerApplication.java
  - config/
    - SecurityConfig.java
    - WebConfig.java
    - PersistenceConfig.java
    - ObservabilityConfig.java
    - OpenApiConfig.java
  - controller/
    - ItemsController.java
    - ItemDetailsController.java
    - UsersController.java
    - AuthController.java
    - SessionsController.java
    - PlaylistsController.java
    - StreamingController.java
    - ImagesController.java
    - SystemController.java
  - dto/
    - request/… (AuthenticateByNameRequest, ItemsQuery, PlaylistRequests, SessionRequests)
    - response/… (AuthenticateResponse, ItemDto, UserDto, PlaylistDto, PlaylistItemDto, SystemInfoDto)
  - domain/
    - model/… (Item, ItemType, AudioTrack, MusicAlbum, MusicArtist, Genre, ImageAsset, Playlist, PlaylistEntry, User, ApiToken, Session, PlayState)
    - service/… (ItemService, StreamingService, TranscodeService, ImageService, UserService, AuthService, PlaylistService, SessionService, LibraryScanService, SearchService)
    - ports/… (ItemRepositoryPort, UserRepositoryPort, PlaylistRepositoryPort, SessionRepositoryPort, PlayStateRepositoryPort, MediaScanner, Transcoder, ImageScaler)
  - infra/
    - persistence/
      - entity/… (ItemEntity, AudioTrackEntity, AlbumEntity, ArtistEntity, GenreEntity, ItemGenreEntity, ImageEntity, PlaylistEntity, PlaylistEntryEntity, UserEntity, ApiTokenEntity, SessionEntity, PlayStateEntity)
      - repository/… (JpaItemRepository, JpaPlaylistRepository, JpaUserRepository, JpaSessionRepository, JpaPlayStateRepository)
      - query/… (ItemSpecifications, ItemProjections)
    - fs/
      - FileSystemMediaScanner.java
      - WatchServiceListener.java
      - JAudioTaggerExtractor.java
    - transcoding/
      - FfmpegTranscoder.java
      - FfmpegProcessManager.java
    - images/
      - ThumbnailatorImageScaler.java
    - security/
      - ApiKeyAuthFilter.java
      - TokenService.java
      - PasswordHasher.java
    - cache/
      - CaffeineCacheConfig.java
  - mapper/
    - ItemMapper.java
    - PlaylistMapper.java
    - UserMapper.java
  - error/
    - GlobalExceptionHandler.java
    - ApiError.java
- src/test/java/com/yaytsa/server/ (planned)
  - unit/… (planned)
  - integration/… (planned)

Tools and frameworks (with evidence-based rationale)

- Java 21 virtual threads (JEP 444): thread-per-request scales for blocking I/O (filesystem, JDBC, FFmpeg). Keeps code imperative with high concurrency.
- Spring Boot 3.3 (Web MVC, Security, Data JPA): mature HTTP, auth, and persistence stack that benefits from virtual threads without reactive complexity.
- PostgreSQL 15+: strong indexing, recursive CTEs for hierarchies, trigram or full-text for search; predictable ACID semantics.
- Spring Data JPA + Specifications: type-safe dynamic filters for /Items; pagination, sorting in DB.
- Flyway: repeatable, deterministic schema migrations.
- Testcontainers: integration tests against real PostgreSQL.
- jaudiotagger: reliable multi-format tag parsing; widely used in tagging tools (e.g., MusicBrainz Picard).
- FFmpeg (external process): de facto standard transcoder; process isolation simplifies failure handling.
- Thumbnailator (or imgscalr): image resizing with good quality/perf trade-offs.
- springdoc-openapi: generated OpenAPI for client integration.
- Caffeine: near-cache for tokens, thumbnails, and small hot-set lookups.
- Micrometer + Prometheus: neutral metrics API for validation and capacity planning.
- Maven: stable build toolchain.
- Lombok: optional; prefer Java records for DTOs to minimize annotation coupling.

Data model (minimal, relational, query-driven)

- users(id UUID, username CITEXT UNIQUE, password_hash, display_name, is_admin, created_at)
- api_tokens(id UUID, user_id, token CHAR(64) UNIQUE, device_id, device_name, created_at, last_used_at, revoked)
- items(id UUID, type ENUM, name, sort_name, parent_id UUID NULL, path TEXT, container TEXT, size_bytes BIGINT, mtime TIMESTAMP, library_root TEXT)
- audio_tracks(item_id PK/FK, album_id FK items, album_artist_id FK items, track_no, disc_no, duration_ms, bitrate, sample_rate, channels, year, codec)
- albums(item_id PK/FK)
- artists(item_id PK/FK)
- genres(id UUID, name TEXT UNIQUE)
- item_genres(item_id FK, genre_id FK, PK(item_id, genre_id))
- images(id UUID, item_id FK, type, path TEXT, width, height, tag TEXT)
- playlists(id UUID, user_id FK, name, created_at)
- playlist_entries(id UUID, playlist_id FK, item_id FK, position INT)
- play_state(id UUID, user_id FK, item_id FK, is_favorite, play_count, last_played_at, playback_position_ms)
- sessions(id UUID, user_id FK, device_id, device_name, now_playing_item_id FK, position_ms, paused, last_update)
  Indexes (critical):
- items(type, parent_id), items(sort_name)
- audio_tracks(album_id), audio_tracks(album_artist_id)
- item_genres(genre_id, item_id)
- playlist_entries(playlist_id, position)
- play_state(user_id, item_id) UNIQUE
- Optional search: trigram index on items.name/sort_name or GIN on to_tsvector for searchTerm

Endpoint behavior mapped to design

- GET /Items
  - Filters: userId, parentId, includeItemTypes, recursive, sortBy, sortOrder, startIndex, limit, searchTerm, artistIds, albumIds, genreIds, isFavorite, fields.
  - Implement with JPA Specifications. For recursive=true, use a PostgreSQL WITH RECURSIVE CTE to materialize descendants of parentId; join with tracks/artists/albums conditionally to avoid unnecessary joins.
  - searchTerm: start with ILIKE + trigram; upgrade to to_tsvector when ranking needed.
  - fields controls expansions (e.g., genres, images) to minimize N+1 and payload size.
- GET /Items/{itemId}
  - Fetch by id; optionally expand fields. 404 if not found or unauthorized.
- GET /Users/{userId}
  - Return profile. Enforce user == authenticated or admin.
- POST /Users/AuthenticateByName
  - Input: username, password, deviceId, deviceName.
  - Verify via BCrypt. Create opaque 256-bit token (server-stored) bound to device; store in api_tokens; return token and user summary.
- POST /Sessions/Logout
  - Revoke token (set revoked=true); idempotent.
- POST /Sessions/Playing, /Sessions/Playing/Progress, /Sessions/Playing/Stopped
  - Upsert session keyed by (userId, deviceId). Update now_playing_item_id and position. On Stopped, update play_state (increment play_count if >50% of duration or >240s played, whichever first).
- POST /Playlists
  - Create playlist for user; name required.
- /Playlists/{playlistId}/Items
  - GET with pagination. POST appends items (validate type=AudioTrack). DELETE removes by entryIds. Use transaction + numeric positions; fill gaps on delete.
- POST /Playlists/{playlistId}/Items/{itemId}/Move/{newIndex}
  - Atomic reorder: shift affected range and set newIndex.
- GET /Audio/{itemId}/stream
  - Params: api_key, deviceId, audioCodec, container, static, audioBitRate.
  - Direct stream if compatible: serve file with byte-range support per RFC 9110 (Range, Content-Range, Accept-Ranges), support HEAD, strong ETag (based on inode+size+mtime or file hash if stable).
  - Transcode when requested/required: start FFmpeg process writing to stdout; cap concurrent transcodes; return 503 + Retry-After when saturated; on client disconnect, destroy process. Document that precise byte-range semantics don’t apply to live transcodes; implement time-based seek (-ss) on start when client asks for offset.
- GET /Items/{itemId}/Images/{imageType}
  - Params: api_key, tag, maxWidth, maxHeight, quality. Resize server-side, cache by (itemId, type, WxH, q, tag). Use ETag and Cache-Control; honor If-None-Match and return 304 when tag matches. Image discovery order: embedded > folder.jpg/cover.jpg > parent > artist.

Streaming details (verifiable)

- Use Spring’s HttpRange utilities to implement partial content correctly (RFC 9110). For static streams, serve ResourceRegion segments with correct 206 and Content-Range. For direct file transfer, use FileChannel.transferTo where available to reduce copies; ensure Accept-Ranges: bytes.
- Validate with curl:
  - curl -I … returns Accept-Ranges and Content-Length for full responses.
  - curl -H "Range: bytes=0-1023" … returns 206 and exactly 1024 bytes.

Scanning and library maintenance

- Full scan on first run across configured roots; incremental scans thereafter.
- Change detection: upsert item by absolute path; if mtime or size changes, re-extract tags; record deletion when files disappear.
- Concurrency: bounded parallelism (e.g., 8–16 concurrent reads) to avoid disk thrash; virtual threads simplify orchestration.
- jaudiotagger for tags and embedded artwork; store artwork as files, not blobs; store a tag/version in DB to bust caches.
- Normalize sort_name (strip leading articles) to stabilize sorting.

Security

- Authentication: Authorization: Bearer <token> header or api_key query param; single resolver normalizes both.
- Tokens: opaque, 256-bit random, server-stored for immediate revocation and device binding; cache token→principal in Caffeine with short TTL.
- Passwords: BCrypt with configurable cost; never store plaintext.
- Authorization: enforce ownership on playlists; restrict item visibility if per-user visibility is later added.
- CORS: only needed if browser clients; otherwise disabled.

Observability and limits

- Metrics: scan throughput, time-to-first-byte, active streams, active transcodes, DB query latency, cache hit rate.
- Backpressure: configurable max transcodes and max concurrent scans. Fail fast with 503 + Retry-After when over capacity.
- Structured logging: userId, deviceId, itemId, requestId, durations.

Implementation plan (deliverables and validation)

- Phase 0: Skeleton and infra (1–2 days)
  - Spring Boot project; enable virtual threads (spring.threads.virtual.enabled=true).
  - Add dependencies (web, security, data-jpa, postgres, flyway, springdoc, caffeine, micrometer, testcontainers, jaudiotagger).
  - Baseline Flyway migration; stub all controllers; token filter scaffold.
- Phase 1: Domain + persistence + /Items basics (3–5 days)
  - Entities, repositories, indexes; JPA Specifications; GET /Items with pagination and filters; GET /Items/{itemId}.
  - Validate with Testcontainers; EXPLAIN ANALYZE to ensure index usage.
- Phase 2: Auth and users (2–3 days)
  - BCrypt, opaque tokens, POST /Users/AuthenticateByName, GET /Users/{userId}, POST /Sessions/Logout.
  - Tests for login, revocation, authorization.
- Phase 3: Library scanning (4–7 days)
  - FileSystemMediaScanner with jaudiotagger; full and incremental scans; deletion detection; optional WatchService.
  - Validate on a sample library; measure files/min.
- Phase 4: Direct streaming (2–4 days)
  - GET /Audio/{itemId}/stream with HttpRange partial content and HEAD; ETag, If-Range/If-None-Match.
  - Validate with curl range tests; confirm 206 correctness.
- Phase 5: Transcoding (5–8 days)
  - FFmpeg adapter; bounded concurrency; kill-on-disconnect; map params to codec/bitrate/container; return 503 when saturated.
  - Load test to enforce caps; confirm CPU stays below target under pressure.
- Phase 6: Sessions and play state (2–4 days)
  - Playing/Progress/Stopped semantics; scrobble threshold; idempotency and race-safety.
- Phase 7: Playlists (2–4 days)
  - Create, list, add/remove items, move with atomic renumbering; pagination; authorization.
- Phase 8: Images (2–4 days)
  - Artwork extraction, resizing, caching; ETag/304 behavior; param handling.
- Phase 9: Hardening and packaging (3–5 days)
  - Complete OpenAPI with examples; add missing indexes; Caffeine caches; Micrometer + Prometheus; Dockerfile and docker-compose; CI with integration tests.

Performance targets (measurable)

- /Items on 50k tracks, filtered/paginated: p95 < 150 ms with indexes and limited expansions.
- Direct streaming time-to-first-byte from SSD: < 100 ms; valid 206 responses for ranges.
- Transcoding: enforce slot cap; return 503 beyond capacity; no process leaks.
- Initial scan: thousands of files/min on SSD; incremental scans in minutes for small deltas.

Bias mitigations

- Virtual threads + MVC chosen over reactive: simpler code with comparable throughput for I/O-bound workloads per JEP 444; can revisit reactive only with evidence of bottlenecks.
- Opaque tokens over JWT: immediate revocation and device binding are operationally safer for a single service; can introduce JWT later if cross-service validation becomes a requirement.
- PostgreSQL over adding a search engine: start with trigram/full-text indexes; introduce external search only if query latency evidence demands it.

---
