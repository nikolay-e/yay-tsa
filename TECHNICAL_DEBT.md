# Technical Debt Register

> Findings from 8 independent code audits (security, refactoring, legacy, architecture).
> Severity: CRITICAL > HIGH > MEDIUM > LOW

---

## CRITICAL

### [C-1] N+1 queries in `convertToDto` — 200+ SQL per page
- **File**: `services/server/src/main/java/com/yaytsa/server/controller/ItemsController.java:411–431`
- **Impact**: 100 tracks on `/Items` = 200+ SQL queries instead of 2–3
- **Fix**: Batch-load `playStates` via `PlayStateService.getPlayStatesForItems()`, batch-load `audioTracks` via `AudioTrackRepository.findAllByIdInWithRelations()`, batch-load album child counts via single `COUNT … GROUP BY` query
- **Status**: Fixed in commit C3

### [C-2] EAGER fetch on `ItemEntity.itemGenres` and `ItemEntity.images`
- **File**: `services/server/src/main/java/com/yaytsa/server/infrastructure/persistence/entity/ItemEntity.java:73–85`
- **Impact**: Every item load fetches genres and images unconditionally; on a page of 100 albums this is 200 extra joins even when neither collection is needed
- **Fix**: Change to `FetchType.LAZY`; add `@EntityGraph` in queries that actually need genres/images
- **Status**: Fixed in commit C4

### [C-3] IDOR — arbitrary `userId` accepted without ownership check on `/Items`
- **File**: `services/server/src/main/java/com/yaytsa/server/controller/ItemsController.java:118–121`
- **Impact**: Any authenticated user can pass another user's UUID as `userId` and receive their play-state data (favorites, play counts)
- **Fix**: In `getItems()`, `getItem()`, and `getAlbumTracks()` — assert that `userId` equals the authenticated principal's ID, or caller is admin
- **Status**: Fixed in commit C5

### [C-4] Genius API token committed to git via `docker-compose.override.yml`
- **File**: `docker-compose.override.yml` (tracked despite being in `.gitignore`)
- **Impact**: Credential leak in git history; any future collaborator or fork exposes the token
- **Fix**: `git rm --cached docker-compose.override.yml`; token must be managed via env-only
- **Status**: Fixed in commit C1

### [C-5] LLM analysis relies on text metadata instead of actual audio signal
- **Files**: `services/server/src/main/java/com/yaytsa/server/domain/service/radio/` (all LLM providers)
- **Impact**: Analysis quality is limited to what can be inferred from title/artist/lyrics text; energy, valence, danceability have no grounding in the audio waveform; results require paid external API keys with ongoing cost and latency
- **Fix**: Replace with Essentia ML pipeline analysing the raw audio file
- **Status**: Fixed in commits A1–A3

---

## HIGH

### [H-1] Dead `spring.cache` config in `application.yml` — `CacheConfig.java` is the single source of truth
- **File**: `services/server/src/main/resources/application.yml:63–77`
- **Impact**: Config drift — the YAML lists cache names that may diverge from what `CacheConfig` actually registers; confusing for maintainers
- **Fix**: Delete `spring.cache.type/caffeine/cache-names` block; keep only `CacheConfig.java`
- **Status**: Fixed in commit C2

### [H-2] `dev` profile uses `ddl-auto: update` — bypasses Flyway
- **File**: `services/server/src/main/resources/application.yml:289`
- **Impact**: Schema changes applied by Hibernate in dev silently diverge from migration files; schema may not match prod when migration runs
- **Fix**: Change to `ddl-auto: none`; rely exclusively on Flyway in all profiles
- **Status**: Fixed in commit C2

### [H-3] Dead `TranscodeJobEntity` / `TranscodeJobRepository` — no usages outside their own files
- **Files**: `TranscodeJobEntity.java`, `TranscodeJobRepository.java`
- **Impact**: Dead code increases cognitive load, remains a Hibernate-managed entity that registers a `transcode_jobs` table
- **Fix**: Delete both files; add `V16__drop_transcode_jobs.sql`
- **Status**: Fixed in commit C6

### [H-4] Duplicate `PathUtils` — `getFilenameWithoutExtension` lives in `infrastructure/fs`
- **Files**: `infrastructure/fs/PathUtils.java`, `util/PathUtils.java`
- **Impact**: Two PathUtils classes in the same project; the infrastructure one has no reason to be in the infrastructure layer
- **Fix**: Move `getFilenameWithoutExtension` to `util/PathUtils.java`; delete `infrastructure/fs/PathUtils.java`; update import in `FfmpegTranscoder.java`
- **Status**: Fixed in commit C6

### [H-5] `parseUuid()` duplicated in every controller
- **Files**: `ItemsController.java`, `PlaylistsController.java`, `StreamingController.java`, `UsersController.java` (and others)
- **Impact**: ~20 lines of identical try/catch UUID parsing repeated N times; bug fix must be applied in every copy
- **Fix**: Extract to `util/UuidUtils.parseUuid(String)`; replace all usages
- **Status**: Fixed in commit C7

### [H-6] No URL validation on service settings — SSRF risk
- **File**: `services/server/src/main/java/com/yaytsa/server/controller/AppSettingsController.java`
- **Impact**: An admin can set `service.separator-url` / `service.lyrics-url` to internal network addresses, turning the backend into an SSRF proxy
- **Fix**: Validate that values are http/https URLs with public IP or whitelisted hostnames; reject RFC 1918 ranges
- **Status**: Fixed in commit C9

### [H-7] Essentia service unavailability not handled — hard failure with no fallback
- **Context**: After replacing LLM with Essentia (A1–A3)
- **Impact**: If `essentia-analyzer` is down, `analyzeTrack` logs a warning but produces no result; batch silently skips tracks without surfacing service health to operators
- **Fix**: Add `isHealthy()` check in `TrackAnalysisService`; use hash-based deterministic fallback (derived from MockLlmProvider logic) when Essentia is unhealthy; log warning on fallback
- **Status**: Fixed in commit C8

### [H-8] Missing composite index on `items(type, sort_name)`
- **Context**: `/Items` with `IncludeItemTypes` filter uses `type` and `sort_name` together in every query
- **Impact**: Queries scan the full index on `sort_name` then filter by `type`, instead of using a selective composite scan
- **Fix**: `CREATE INDEX CONCURRENTLY idx_items_type_sort_name ON items(type, sort_name)`
- **Status**: Fixed in commit C10

### [H-9] `dev.log` file present at project root — never tracked, never gitignored explicitly
- **File**: `dev.log` at project root
- **Impact**: Accidental `git add .` commits the log; reveals internal request traces / stack traces
- **Fix**: Add `dev.log` to `.gitignore` (root-level pattern)
- **Status**: Fixed in commit C1

### [H-10] `BATCH_PAUSE_MS = 1000` hardcoded — analysis throughput cannot be tuned
- **File**: `TrackAnalysisService.java:26`
- **Impact**: For large libraries (10k+ tracks) the 1s pause makes a full analysis run take hours; for fast local services (Essentia) this is unnecessary throttling
- **Note**: Low priority post-Essentia; Essentia is fast enough that pause can be reduced; left for operator tuning

### [H-11] `TrackAudioFeaturesEntity.rawResponse` field kept after LLM removal — schema debt
- **File**: `TrackAudioFeaturesEntity.java`
- **Note**: After A3 the field is populated with `"essentia-musicnn"` sentinel; column is still TEXT. Acceptable as an audit field for now.

### [H-12] `AudioSeparatorClient` URL cached with Caffeine `audio-separator-url` — not in `CacheConfig`
- **File**: `AudioSeparatorClient.java:55`
- **Impact**: `@Cacheable` for a cache name not registered in `CacheConfig` silently falls back to no-op in strict mode; cache name must be registered
- **Note**: Minor; add to `CacheConfig` cache names list when addressing C2

---

## MEDIUM

### [M-1] `getAlbumTracks` — N+1 on `playState` per track (no batch load)
- **File**: `ItemsController.java:231–246`
- **Impact**: Returning 50 album tracks = 50 individual `play_state` lookups
- **Fix**: Batch-load play states for all track IDs before mapping

### [M-2] `SmartRadioService` generates playlists without pagination — full table scan
- **File**: `SmartRadioService.java`
- **Impact**: `findUnanalyzedTrackIdsNative` and filter queries may scan large result sets in memory
- **Fix**: Add `LIMIT` to all radio filter queries at repository level

### [M-3] `AudioTrackEntity` lyrics stored as full TEXT in DB row — large payloads in every track fetch
- **Impact**: Lyrics can be 10s of KB; including them in every `/Items` response unnecessarily inflates payloads
- **Fix**: Exclude lyrics from default DTO projection; return only when `Fields=Lyrics` requested

### [M-4] `ItemService.queryItems` — no maximum page size enforcement at service layer
- **File**: `ItemsController.java:137` enforces `MAX_PAGE_SIZE` but only in the controller; service layer accepts any limit
- **Fix**: Enforce limit at service layer too

### [M-5] `SessionEntity.lastUpdate` not indexed — slow session dashboard queries
- **Fix**: Add index `sessions(last_update DESC)` for recent-session queries

### [M-6] `PlayStateService.getPlayState` issues single query per item — hot path
- **Fix**: Add `getPlayStatesForItems(userId, Collection<UUID>)` returning `Map<UUID, PlayStateEntity>`

### [M-7] `FfmpegTranscoder` spawns unbounded processes if `maxConcurrent` not set
- **Fix**: Assert non-null `maxConcurrent` at startup

### [M-8] `LibraryScanService` has no scan-in-progress guard — concurrent scans race
- **Fix**: Add `AtomicBoolean scanRunning` guard analogous to `TrackAnalysisService.batchRunning`

### [M-9] `ImageEntity` path stored as relative string — breaks on `LIBRARY_ROOTS` change
- **Fix**: Store as relative-to-library-root path; resolve at serve time

### [M-10] `SearchTerm` ILIKE query not using trigram index — full sequential scan above 1k items
- **Fix**: Ensure `pg_trgm` extension and `GIN` trigram index on `items.name` are in a migration

### [M-11] `AlbumRepository.countByArtistId` uses JPQL count — no query cache
- **Fix**: Add `@QueryHints` with `HINT_CACHEABLE` for read-heavy count queries

### [M-12] `AudioSeparatorClient.separate` throws `RuntimeException` on failure — caller has no type info
- **Fix**: Introduce typed `AudioSeparationException`

### [M-13] `AppSettingsService.get` with env-var fallback uses `System.getenv` directly — not testable
- **Fix**: Inject `Environment` bean; easier to mock in tests

### [M-14] `TrackAudioFeaturesRepository.findUnanalyzedTrackIdsNative` is a native query — Flyway changes break it silently
- **Fix**: Rewrite as JPQL with subquery `WHERE NOT EXISTS (SELECT 1 FROM TrackAudioFeaturesEntity …)`

### [M-15] `docker-compose.yml` backend service has no memory limit — OOM risk in shared environments
- **Fix**: Add `mem_limit: 512m` or equivalent resource constraints

---

## LOW

### [L-1] `LlmPromptBuilder` remains after LLM removal — delete in follow-up if not repurposed
### [L-2] `@Tag` annotations on controllers reference "Items", "Settings" — inconsistent casing vs OpenAPI spec
### [L-3] `ItemsController.parseCommaSeparatedList` and `parseUuidList` are controller-private — should move to `UuidUtils` / `RequestUtils`
### [L-4] `QueryResultResponse` uses `Math.toIntExact` on `totalElements` — throws on libraries > 2B items (acceptable but surprising)
### [L-5] `AudioTrackEntity.findRandomTrackIds` uses `ORDER BY RANDOM()` — table-scan for large libraries; replace with `TABLESAMPLE SYSTEM` for >100k tracks
### [L-6] `AppSettingsEntity.updatedAt` not exposed in any API response — useful for debugging config drift
### [L-7] `AudioSeparatorClient` build creates two `RestClient` instances with duplicate builder config — extract factory method
### [L-8] `ItemMapper.toDto` called in three places with different null-guards — inconsistent DTO shape depending on call site
### [L-9] `SessionEntity` stores `now_playing_item_id` as FK — cascade delete removes session when item deleted; intent unclear
### [L-10] No structured logging correlation ID — traces across async virtual threads are not correlated
