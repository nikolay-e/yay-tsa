# Yay-Tsa — Features Overview

A self-hosted music streaming system: multi-protocol Kotlin server (Spring Boot 3.4 / JDK 21) paired with a React 19 PWA. Speaks four protocols simultaneously over a single state engine.

> Test acceptance criteria (AC-IDs referenced by Playwright/integration tests) live in [`FEATURES.md`](FEATURES.md). This document is a **capability inventory** — what the product does, end-user perspective.

---

## 1. Backend — Protocols & Core

### 1.1 Jellyfin Adapter (HTTP REST)

#### Yaytsa custom extensions (`/v1/*`)

| Verb   | Path                                  | Purpose                                                           |
| ------ | ------------------------------------- | ----------------------------------------------------------------- |
| POST   | `/v1/sessions`                        | Start adaptive listening session (optional seed track)            |
| PATCH  | `/v1/sessions/{id}/state`             | Update energy / intensity / mood / attention mode                 |
| DELETE | `/v1/sessions/{id}`                   | End session                                                       |
| GET    | `/v1/sessions/active`                 | List active sessions for current user                             |
| GET    | `/v1/sessions/{id}/queue`             | Current adaptive queue with intent labels                         |
| POST   | `/v1/sessions/{id}/queue/refresh`     | Regenerate queue tail                                             |
| POST   | `/v1/sessions/{id}/signals`           | Record skip / completion / thumbs-up / thumbs-down                |
| GET    | `/v1/users/{id}/preferences`          | Preference contract (hard rules, soft prefs, DJ style, red lines) |
| PUT    | `/v1/users/{id}/preferences`          | Update preference contract                                        |
| GET    | `/v1/recommend/radio/seeds`           | Seed tracks for radio (ML affinities + favorites fallback)        |
| GET    | `/v1/recommend/daily-mix`             | Personalized daily mix                                            |
| GET    | `/v1/recommend/search`                | Embedding-based semantic search                                   |
| GET    | `/v1/me/devices`                      | Registered devices                                                |
| GET    | `/v1/me/devices/events`               | SSE — device state changes                                        |
| GET    | `/v1/me/devices/commands`             | SSE — remote-control commands                                     |
| POST   | `/v1/me/devices/heartbeat`            | Keep-alive                                                        |
| POST   | `/v1/me/devices/{sessionId}/command`  | Send command to specific device                                   |
| POST   | `/v1/me/devices/{sessionId}/transfer` | Transfer playback lease                                           |

#### Karaoke (`/Karaoke/*`)

- `GET /Karaoke/enabled` — feature availability flag
- `GET /Karaoke/{trackId}/status` — `NOT_STARTED` / `PROCESSING` / `READY` / `FAILED`
- `POST /Karaoke/{trackId}/process` — enqueue for stem separation
- `GET /Karaoke/{trackId}/instrumental` — stream instrumental stem (Range-aware)
- `GET /Karaoke/{trackId}/vocals` — stream vocal stem (Range-aware)
- `GET /Karaoke/{trackId}/status/stream` — SSE status updates

#### Lyrics (`/Lyrics/*`)

- `GET /Lyrics/{trackId}` — timed lines (`text`, `startMs`, `endMs`)
- `POST /Lyrics/{trackId}/fetch` — same shape (lazy load)

Source: `.lyrics/*.lrc` sidecar files under album folders. LRC parser supports compact multi-timestamp lines and `[ar:]` / `[ti:]` header tags.

#### Playback sessions (`/Sessions/*`)

`GET /Sessions`, `POST /Sessions/Playing`, `POST /Sessions/Playing/Progress`, `POST /Sessions/Playing/Stopped`, `POST /Sessions/Logout`.

#### Library browsing

- `GET /Items` — flexible browse (`ParentId`, `IncludeItemTypes`, `ArtistIds`, `AlbumIds`, `SearchTerm`, `IsFavorite`, `SortBy`)
  - `SortBy=DatePlayed/Personalized/PlayCount` → ML-affinity-ordered (per-user), favorites fallback, random sample fallback
- `GET /Items/{id}` — track / album / artist / playlist metadata
- `GET /Items/{id}/PlaybackInfo` — media-source info for stream URL
- `GET /Items/{id}/Images/{type}` — cover art (album, artist, track)
- `GET /UserViews` — library root collections
- `GET /Artists`, `GET /Artists/AlbumArtists` — alphabetized browse with `ChildCount` (album count)
- `GET /Search/Hints` — quick-suggest

#### Streaming

- `GET /Audio/{id}/stream` — native-format, HTTP Range support, codec-aware Content-Type
- `GET /Audio/{id}/universal` — universal-format (transcoded if needed)

#### Favorites

- `POST /UserFavoriteItems/{id}` — favorite
- `DELETE /UserFavoriteItems/{id}` — unfavorite
- `POST /Items/FavoriteOrder` — reorder

#### Playlists

`POST /Playlists` (with optional initial tracks), `GET /Playlists/{id}/Items`, `POST /Playlists/{id}/Items`, `DELETE /Playlists/{id}/Items`, `POST /Playlists/{id}/Items/{itemId}/Move/{newIndex}`, `DELETE /Items/{playlistId}`.

#### Auth & system

`POST /Users/AuthenticateByName`, `POST /Sessions/Logout`, `GET /Users/Me`, `GET /Users/{id}`, `GET /System/Info`, `GET /System/Info/Public`, `GET|POST /System/Ping`.

#### Admin (`/Admin/*`, requires `isAdmin`)

- Users: `GET /Admin/Users`, `POST /Admin/Users`, `DELETE /Admin/Users/{id}`, `POST /Admin/Users/{id}/ResetPassword`
- Cache: `GET /Admin/Cache/Stats`, `DELETE /Admin/Cache`, `DELETE /Admin/Cache/Images/{id}`
- Library: `POST /Admin/Library/Rescan`, `GET /Admin/Library/ScanStatus`

#### Upload

- `POST /tracks/upload` — multipart audio file upload

### 1.2 OpenSubsonic Adapter (`/rest/*`)

XML/JSON via `f=json|xml`. Verified compatible with **Symfonium, Finamp, Feishin, DSub**.

| Endpoints                                                                       | Notes                                           |
| ------------------------------------------------------------------------------- | ----------------------------------------------- |
| `ping`, `getLicense`, `getOpenSubsonicExtensions`, `getUser`, `getMusicFolders` | system                                          |
| `getArtists`, `getArtist`, `getAlbum`, `getSong`, `getAlbumList2`               | library                                         |
| `search3`                                                                       | full-text search                                |
| `star`, `unstar`, `getStarred2`                                                 | favorites                                       |
| `getPlaylists`, `getPlaylist`, `createPlaylist`, `deletePlaylist`               | playlists                                       |
| `scrobble`, `getNowPlaying`                                                     | playback (scrobble is currently a no-op accept) |

### 1.3 MPD Adapter (TCP port 6600)

**Implemented:** `ping`, `status`, `currentsong`, `play [pos]`, `pause`, `stop`, `next`, `previous`, `playlistinfo`, `lsinfo [uri]`, `search`, `find`, `list <tag>`, `outputs`, `tagtypes`, `commands`, `command_list_begin|_ok_begin|_end`.

**Stubs (no-op):** `clear`, `add`, `setvol`, `repeat`, `random`, `single`, `consume`, `idle`, `noidle`, `close`.

Compatible with **ncmpcpp, mpc, Cantata, M.A.L.P.**

### 1.4 MCP Adapter (Model Context Protocol)

LLM-callable tool surface for Claude:

- `search_library(query)` — artists + albums + tracks (≤20 each)
- `browse_artists(limit, offset)` — paged listing
- `get_album(id)` — album details with tracks
- `get_playback_state` — session state + current track
- `play`, `pause`, `skip_next`, `skip_previous`
- `list_playlists` — user's playlists

### 1.5 Eight Bounded Contexts

| Context         | User-visible behavior                                                                                      |
| --------------- | ---------------------------------------------------------------------------------------------------------- |
| **Auth**        | Login, API tokens, multi-device sessions, admin user management                                            |
| **Playback**    | Single authoritative queue, play/pause/stop/skip, device lease, lazy position tracking                     |
| **Library**     | Browse / search artists / albums / tracks / genres; cover art                                              |
| **Adaptive**    | LLM-driven queue rewrites, listening sessions, intent-labelled queue edits                                 |
| **Preferences** | Favorites (with ordering), preference contract for the DJ                                                  |
| **Playlists**   | User-curated ordered collections (public/private), reorder, share                                          |
| **ML**          | Audio embeddings (Discogs / MusicNN / CLAP / MERT), per-track features, per-user affinity, HNSW similarity |
| **Karaoke**     | Vocal/instrumental stems, readiness state, synchronized lyrics                                             |

---

## 2. Workers & Background Subsystems

### 2.1 Library Scanner — `infra-library-scanner`

- Walks `MUSIC_PATH`, reads FLAC/MP3/OGG/AAC tags via JAudioTagger
- Folder-derived fallbacks when tags missing (`Artist/YYYY - Album/NN - Track.flac`)
- Strips track-number prefix from filename when title falls back to filename
- Treats placeholder tags (`#####`, `????`) as missing → falls back to folder/filename
- Skips zero-duration files (CD pre-gap, corrupt headers)
- Discovers cover art (`cover.jpg`, `folder.jpg`, `front.jpg`, `album.jpg`, `.webp` variants)
- Case-insensitive ICU lookup on `source_path` so legacy capitalized ETL rows heal on next scan (Cyrillic-safe)
- Primary-artist split for compound `ARTIST` tags (`feat.`, `,`, `;`, `&`, etc.)
- **Writes:** `core_v2_library.entities`, `audio_tracks`, `entity_genres`, `images`

### 2.2 ML Feature Extractor — `infra-ml-worker`

- Discovers tracks missing embeddings, invokes external Python extractor per track (`ProcessBuilder`)
- Computes Discogs (1280d), MusicNN (200d), CLAP (512d), MERT (768d) embeddings + scalar features (BPM, energy, valence, danceability, loudness, spectral complexity)
- Aggregates user taste profiles and per-track affinity scores
- **Trigger:** `@Scheduled fixedDelay=300 s` (configurable via `yaytsa.ml.poll-interval-ms`)
- **Enable flag:** `yaytsa.ml.enabled=true` + `yaytsa.ml.extractor-script=<path>`
- **Writes:** `core_v2_ml.track_features`, `taste_profiles`, `user_track_affinity`

### 2.3 LLM Orchestrator — `infra-llm`

- Polls active listening sessions every 30 s
- Reads recent playback signals (≤20), current queue, taste profile, top-affinity tracks (≤50), preference contract
- Calls Anthropic Claude API (`claude-sonnet-4-20250514` by default), generates queue edits with intent labels + reasoning
- Atomically appends new tail (preserves currently playing track)
- Audit-logs each decision (prompt hash, token counts, latency, applied edits) in `core_v2_adaptive.llm_decision_log`
- **Trigger:** `@Scheduled fixedDelay=30 s`
- **Enable flag:** `yaytsa.llm.enabled=true` + `ANTHROPIC_API_KEY` env var

### 2.4 Karaoke Worker — `infra-karaoke-worker`

- Picks unprocessed tracks, separates into vocal/instrumental WAV stems
- Two deployment modes:
  - In-process Demucs (`DEMUCS_COMMAND=demucs`) — dev / CPU-only
  - HTTP sidecar (`KARAOKE_SEPARATOR_URL` → BS-Roformer / Hybrid Demucs on GPU) — production
- `karaoke_fail_count` column rate-limits retries (skip after 3 failures)
- **Trigger:** `@Scheduled fixedDelay=600 s`
- **Enable flag:** `yaytsa.karaoke.enabled=true`
- **Writes:** `core_v2_karaoke.assets` + WAV files under `/app/stems/{trackId}/`

### 2.5 Notifications Outbox Poller — `infra-notifications`

- Reads `core_v2_shared.outbox` (events written transactionally with aggregate changes)
- Fans out via WebSocket to connected clients (per-protocol payload translation)
- At-least-once delivery; marks `published_at` after successful send
- **Trigger:** `@Scheduled fixedDelay=1 s`, batch 50

### 2.6 Media Streaming — `infra-media`

- Serves audio with HTTP Range support, codec-aware Content-Type (`audio/flac`, `audio/mpeg`, `audio/opus`, …)
- Filesystem read with path canonicalisation + NOFOLLOW_LINKS (TOCTOU mitigation)
- Writes bytes directly to `HttpServletResponse` (bypasses Spring `HttpMessageConverter` pipeline for binary payloads)

### 2.7 Audio Separator Sidecar — `services/audio-ml/`

- Python FastAPI app running BS-Roformer (default) or Hybrid Demucs on GPU
- Companion to the karaoke worker; same stems PVC shared with backend (separator writes, backend reads)
- Health endpoint reports model + device (`cuda`/`cpu`)

---

## 3. Frontend — PWA & Mobile

### 3.1 Pages / routes

| Route          | Purpose                                                                                                |
| -------------- | ------------------------------------------------------------------------------------------------------ |
| `/login`       | Username + password, "Remember me", music-library subtitle                                             |
| `/`            | **Radio** seeds row (DJ cluster cards) + **Daily Mix** (personalized)                                  |
| `/albums`      | Grid with sort (name, artist, recently played, date added, year), infinite scroll                      |
| `/albums/:id`  | Album detail with track list, cover, year, Play / Shuffle                                              |
| `/artists`     | Grid with sort + search, `ChildCount`-driven "N albums" label                                          |
| `/artists/:id` | Artist overview + discography with per-album Play                                                      |
| `/songs`       | Virtualized track list, text + AI semantic search, sort options                                        |
| `/favorites`   | Tabs: Songs / Albums / Artists, drag-to-reorder, infinite scroll                                       |
| `/settings`    | Library rescan, force-reload, DJ Preferences, About, user mgmt (admin), Sign out, Android APK download |

### 3.2 Player

- HTML5 `<audio>` with **dual elements** for crossfade / seamless karaoke switch (150 ms)
- **Promise-chain serialization** for load/play (handles browser cancelling play() during load())
- **Gapless** transitions via secondary element preload
- **Volume / mute** persisted to `localStorage`; **Web Audio gain node** for normalization
- **Seek** (synchronous timing-store update; doesn't lose updates to RAF)
- **Shuffle** (randomizes queue order, restores on toggle off)
- **Repeat** (off → all → one → off cycle)
- **History stack** (50-track limit) for back navigation
- **Media Session API** — lock-screen / notification controls, artwork 256×512, play/pause/next/prev/seek
- **Background tab** — `visibilitychange` recovery, AudioContext resume on user gesture, wake lock on play
- **Multi-device sync** — `GroupSyncPanel`, presence heartbeat, remote-control + transfer via SSE
- **Karaoke mode** — toggle vocal/instrumental stream at current position (status: PROCESSING/READY/FAILED)
- **Synced lyrics** — timed line-by-line highlighting, modal overlay (Escape to close)
- **Adaptive DJ** — queue auto-extends from radio seed; respects preference contract

### 3.3 Library browsing

- Infinite scroll via intersection observer
- Sort options: name / artist / recently-played / date-added / year (asc/desc) — persisted via `useSortPreference`
- Server-state cache (TanStack Query) with album / artist / track shape preserved
- AI semantic search (embedding-backed) on `/songs`
- Heart-toggle on every track row / album / artist; consistent across tabs

### 3.4 PWA + mobile

- **Service worker** — NetworkFirst for navigation (10 s timeout → cache), StaleWhileRevalidate for artwork (500 entries / 30 d), NetworkOnly for `/api/Audio/*/stream`
- **Manifest** — `display: standalone`, dark theme (`#0f0f0f`), maskable icon, music category
- **Apple support** — `apple-touch-icon`, `apple-mobile-web-app-capable`, translucent status bar
- **Mobile UX** (412×915 Pixel-7 baseline):
  - Bottom tab bar (Home / Favorites / Artists / Albums / Songs) below `md:` breakpoint
  - Sidebar on desktop; mini PlayerBar above tab bar on mobile
  - Full-screen player modal (swipe-down to dismiss)
  - 44 × 44 px minimum touch targets, momentum scrolling, safe-area insets
  - Karaoke / lyrics / queue reachable from full-player on mobile
- **Android wrapper** (`apps/android-app/`) — WebView activity, `WAKE_LOCK` + `FOREGROUND_SERVICE` permissions, foreground service type `mediaPlayback`, native media-notification

### 3.5 Settings / admin

- **DJ Preferences** (preference contract):
  - Hard rules: `maxArtistConsecutive`, `noRepeatHours`
  - Discovery slider 0–100 (Conservative ≤10 → Adventurous >85)
  - Red lines: blocklist of artists / genres / keywords
- **Library** — rescan (POST `/Admin/Library/Rescan`, 409 if already running)
- **Force Reload** — unregisters every service worker + deletes all `caches.keys()` + reloads
- **Album upload** — batch multipart, auto-rescan
- **About** — build SHA + production/staging chip
- **User management** (admin only) — list, create, delete, password-reset

---

## 4. Infrastructure & Operations

### 4.1 Deployment topology

| Namespace               | Contents                             |
| ----------------------- | ------------------------------------ |
| `yay-tsa-production`    | PWA (nginx), audio-separator sidecar |
| `yay-tsa-v2-production` | Spring Boot backend                  |
| `shared-database`       | CNPG `shared-postgres` cluster       |

**Helm charts:**

- `charts/yay-tsa/` — PWA + audio-separator
- `charts/yay-tsa-v2/` — backend with `secure-headers`, `strip-api` Traefik middlewares; Subsonic ingress; ServiceMonitor; PDB; ConfigMap-driven feature flags (ML / LLM / Karaoke / MPD)

**Images:** `frontend`, `yay-tsa-v2/backend`, `audio-separator`, `feature-extractor` (built, not currently deployed) — all multi-arch (amd64 + arm64), tagged `main-<sha7>`.

**Database:** single `yaytsa_production` DB with 8 per-context schemas + `core_v2_shared`. Extensions: pgvector (HNSW), pg_trgm, citext, pgcrypto.

**Storage:** `audio-separator-stems-pvc` (100 Gi default, shared between separator and backend); hostPath mount for `/media` music library (read-only).

### 4.2 Security & auth

- **HSTS** at Traefik secure-headers middleware: `max-age=63072000; includeSubDomains; preload` (forceSTSHeader)
- **Bearer tokens** — 256-bit SecureRandom, SHA-256 at rest, opaque, device-bound
- **BCrypt** password hashing (strength 13)
- **Five auth surfaces** accepted: `Authorization: Bearer`, `X-Emby-Token`, `X-Emby-Authorization` header, `?api_key=` query, `yay_token` cookie
- **Rate limit** — 10 req / 60 s / (IP, path) on `/Users/AuthenticateByName` and `/rest/*` (returns 429)
- **Admin guard** on every `/Admin/*` endpoint (`requireAdmin()` checks `isAdmin` from `JellyfinAuthentication` or DB)
- **CSP / X-Frame-Options / X-Content-Type-Options** — applied at backend + Traefik

### 4.3 CI/CD

| Workflow            | Triggers                                         | Output                                                                                             |
| ------------------- | ------------------------------------------------ | -------------------------------------------------------------------------------------------------- |
| `ci.yml`            | push to `main` (paths excluding `yay-tsa-v2/**`) | Frontend + audio-separator + feature-extractor images, Android APK, Lighthouse perf budget, AutoQA |
| `v2-ci.yml`         | push to `main` (paths `yay-tsa-v2/**`)           | Gradle build + test, backend Docker image                                                          |
| `release-chart.yml` | push to `main` (charts/\*\*) + version tags      | Helm chart package → published to gh-pages                                                         |

- **Image tagging** — `main-<sha7>` for dev, `v<semver>` for releases
- **ArgoCD Image Updater** monitors GHCR, writes back to gitops repo, auto-syncs
- **Multi-arch** via `docker/setup-buildx-action` + registry layer cache
- **Helm chart versioning** — `<base>-main.r<padded-run>-g<sha7>` so each build sorts higher

### 4.4 Quality gates

- **SonarCloud** (`nikolay-e_yay-tsa`) — quality gate must be OK; admin via API (Basic auth, Keychain token)
- **Pre-commit hooks** — Prettier, ESLint, ktlint via Spotless, semgrep, gitleaks, detect-secrets, codespell, yamllint, helm-lint, type-coverage ≥90 %, jscpd, Commitlint
- **AutoQA pipeline** (`nikolay-e/autoqa` action, SHA-pinned):
  - Crawler (Playwright login mode for CF bypass) + axe-core a11y
  - Schemathesis Stateful + Fuzzing
  - ZAP API scan with absolute `servers[]` injection
  - Mechanical M1–M6 property checks (Unicode round-trip, tofu glyph runs, adjacent dup rows, filename-artifact titles, placeholder text, mutual distinguishability)
  - Bumped to latest `main` on every `/qa` pass
- **E2E** — Playwright (desktop chromium + mobile Pixel 7 + webkit projects)
- **Integration tests** (`packages/core`) — real backend, real DB, real auth flow

### 4.5 Observability

- **Prometheus ServiceMonitor** scrapes `/manage/prometheus` (Spring Actuator + Micrometer)
- **Health probes** — `/manage/health/liveness` (process-only) + `/manage/health/readiness` (DB ping 2 s timeout)
- **Logs** — backend INFO default; `GlobalExceptionHandler` returns RFC 7807 Problem Details

### 4.6 Performance targets

| Metric                               | Target                  |
| ------------------------------------ | ----------------------- |
| Frontend bundle (gzipped)            | < 150 KB                |
| First Contentful Paint               | < 1.2 s (3G Lighthouse) |
| Time to Interactive                  | < 2.5 s                 |
| Frontend memory (idle)               | < 50 MB                 |
| `/Items` p95 (50 k tracks, filtered) | < 150 ms                |
| Streaming TTFB (SSD)                 | < 100 ms                |
| Library scan throughput              | thousands of files/min  |
