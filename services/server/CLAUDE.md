# yay-tsa-server

> Extends [../../CLAUDE.md](../../CLAUDE.md) - inherits workspace conventions (integration tests only, no docstrings, Russian communication)

Java-based Jellyfin-compatible media server implementation.

## Design Overview

A single Java 21 Spring Boot modular monolith with a layered architecture (controllers → domain services → infrastructure), PostgreSQL-backed metadata, filesystem scanning via jaudiotagger, and HTTP byte-range streaming that complies with RFC 9110 solves the two core hard problems: fast, flexible queries over large libraries and correct, efficient streaming with seek support. Use opaque, revocable device-bound tokens and Java 21 virtual threads to keep the code simple while scaling blocking I/O.

### Data Model

See Flyway migrations in `src/main/resources/db/migration/` for the current schema (V1-V29).

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

---
