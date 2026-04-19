# Features Specification

Feature and acceptance criteria registry for yay-tsa.
Tests reference AC-IDs via `@Feature` / `@Tag` annotations (Java) and `test.describe` titles (Playwright).

---

## FEAT-AUTH: Authentication

Device-bound opaque token authentication with immediate revocation.

| AC    | Criterion                                                 | Layer    |
| ----- | --------------------------------------------------------- | -------- |
| AC-01 | Valid credentials return token and user info              | API, E2E |
| AC-02 | Invalid credentials return 401                            | API, E2E |
| AC-03 | Logout revokes token — subsequent requests return 401     | API, E2E |
| AC-04 | Double logout with revoked token returns 401              | API      |
| AC-05 | `api_key` query param authenticates same as Bearer header | API      |
| AC-06 | Session persists across page reload (Remember me)         | E2E      |
| AC-07 | Unauthenticated access redirects to /login                | E2E      |
| AC-08 | Protected route redirect preserves original URL           | E2E      |

---

## FEAT-ITEMS: Library Browsing

Paginated, filterable, searchable music library queries.

| AC    | Criterion                                                    | Layer    |
| ----- | ------------------------------------------------------------ | -------- |
| AC-01 | Paginated album listing with StartIndex/Limit                | API      |
| AC-02 | Search by term returns matching results                      | API, E2E |
| AC-03 | Search with nonexistent term returns empty results           | API, E2E |
| AC-04 | Limit=0 returns 200 with valid response                      | API      |
| AC-05 | Negative limit returns 200 (graceful handling)               | API      |
| AC-06 | Unknown IncludeItemTypes returns 200 (not 500)               | API      |
| AC-07 | StartIndex beyond total returns empty Items with valid count | API      |
| AC-08 | Mixed valid/invalid types returns results for valid types    | API      |
| AC-09 | Album grid displays with covers and metadata                 | E2E      |
| AC-10 | Album detail page shows tracks                               | E2E      |
| AC-11 | Pagination / infinite scroll loads more albums               | E2E      |
| AC-12 | Back navigation returns to album list                        | E2E      |

---

## FEAT-SEARCH: Search

Full-text search across library items.

| AC    | Criterion                                     | Layer |
| ----- | --------------------------------------------- | ----- |
| AC-01 | Partial text matches albums                   | E2E   |
| AC-02 | No results shows empty state                  | E2E   |
| AC-03 | Clearing search restores full list            | E2E   |
| AC-04 | Results update as user types                  | E2E   |
| AC-05 | Opening album from search navigates correctly | E2E   |
| AC-06 | Special characters handled without errors     | E2E   |
| AC-07 | Search is case-insensitive                    | E2E   |
| AC-08 | Search persists during navigation             | E2E   |

---

## FEAT-PLAYBACK: Audio Playback

Play, pause, skip, seek, and queue management.

| AC    | Criterion                               | Layer |
| ----- | --------------------------------------- | ----- |
| AC-01 | Playing album starts first track        | E2E   |
| AC-02 | Play/pause toggle works                 | E2E   |
| AC-03 | Next track advances queue               | E2E   |
| AC-04 | Previous track goes back in queue       | E2E   |
| AC-05 | Player bar shows current track info     | E2E   |
| AC-06 | Player persists across page navigation  | E2E   |
| AC-07 | Specific track playback from album page | E2E   |
| AC-08 | Seeking changes playback position       | E2E   |
| AC-09 | Auto-advance on track end               | E2E   |
| AC-10 | Keyboard controls (Space/Enter)         | E2E   |
| AC-11 | Rapid skip clicks handled without crash | E2E   |

---

## FEAT-QUEUE: Queue Management

Playback queue ordering, repeat, and shuffle.

| AC    | Criterion                            | Layer |
| ----- | ------------------------------------ | ----- |
| AC-01 | Album play populates queue           | E2E   |
| AC-02 | Auto-advance to next track in queue  | E2E   |
| AC-03 | Queue persists during navigation     | E2E   |
| AC-04 | New album play replaces queue        | E2E   |
| AC-05 | Repeat mode cycles (off → one → all) | E2E   |
| AC-06 | Shuffle toggles on/off               | E2E   |

---

## FEAT-VOLUME: Volume Control

Volume adjustment with persistence.

| AC    | Criterion                                     | Layer |
| ----- | --------------------------------------------- | ----- |
| AC-01 | Volume adjustable (0%, 50%, 100%)             | E2E   |
| AC-02 | Volume persists in localStorage               | E2E   |
| AC-03 | Volume restored on page reload                | E2E   |
| AC-04 | Volume persists across playback state changes | E2E   |
| AC-05 | Volume persists during track skipping         | E2E   |

---

## FEAT-STREAM: Audio Streaming

RFC 9110-compliant byte-range streaming with seek support.

| AC    | Criterion                                          | Layer |
| ----- | -------------------------------------------------- | ----- |
| AC-01 | Full stream returns audio bytes with 200           | API   |
| AC-02 | Range request returns 206 Partial Content          | API   |
| AC-03 | Response body length matches Content-Range header  | API   |
| AC-04 | HEAD request returns Content-Length without body   | API   |
| AC-05 | Out-of-range request returns 416 or graceful error | API   |

---

## FEAT-PLAYLISTS: Playlists

CRUD operations and item management for user playlists.

| AC    | Criterion                                | Layer |
| ----- | ---------------------------------------- | ----- |
| AC-01 | Create playlist returns ID               | API   |
| AC-02 | Get playlist by ID returns playlist data | API   |
| AC-03 | Add item to playlist succeeds            | API   |
| AC-04 | Update playlist name persists            | API   |
| AC-05 | Delete playlist removes it               | API   |

---

## FEAT-USERS: User Profile & Favorites

User info retrieval and favorite toggling.

| AC    | Criterion                                           | Layer    |
| ----- | --------------------------------------------------- | -------- |
| AC-01 | Get user by ID returns profile                      | API      |
| AC-02 | Get user items paginated                            | API      |
| AC-03 | Get single user item by ID                          | API      |
| AC-04 | Mark track as favorite toggles state                | API, E2E |
| AC-05 | Favorited item appears in favorites endpoint        | API, E2E |
| AC-06 | Favorites page shows tabs (tracks, albums, artists) | E2E      |
| AC-07 | Rapid double-click on favorite handled safely       | E2E      |

---

## FEAT-SYSTEM: System Info

Public server metadata and health monitoring.

| AC    | Criterion                                                | Layer |
| ----- | -------------------------------------------------------- | ----- |
| AC-01 | Public system info returns server name, version, product | API   |
| AC-02 | Health check returns UP status                           | API   |

---

## FEAT-IMAGES: Album Artwork

Image retrieval with format conversion and caching.

| AC    | Criterion                                         | Layer |
| ----- | ------------------------------------------------- | ----- |
| AC-01 | Get album image with format parameter (e.g. webp) | API   |
| AC-02 | ETag-based caching returns 304 Not Modified       | API   |

---

## FEAT-SESSIONS: Listening Sessions

DJ/Radio session lifecycle.

| AC    | Criterion                                         | Layer    |
| ----- | ------------------------------------------------- | -------- |
| AC-01 | Create session with seed track enables radio mode | API, E2E |
| AC-02 | Create session without seed — non-radio mode      | API      |
| AC-03 | End session sets endedAt timestamp                | API      |
| AC-04 | DJ populates queue after session start            | E2E      |

---

## FEAT-KARAOKE: Karaoke / Vocal Separation

Audio stem separation via BS-Roformer sidecar.

| AC    | Criterion                                                       | Layer |
| ----- | --------------------------------------------------------------- | ----- |
| AC-01 | Status endpoint for unprocessed track returns pending/not-found | API   |
| AC-02 | Process request returns 202 Accepted                            | API   |
| AC-03 | Instrumental/vocals return 404 when stems unavailable           | API   |
| AC-04 | Karaoke button toggles in player                                | E2E   |
| AC-05 | Background backfill processes library prioritized by favorites  | API   |

---

## FEAT-RADIO: Radio Seed Generation

Recommendation seeds from listening history and embeddings.

| AC    | Criterion                                                | Layer |
| ----- | -------------------------------------------------------- | ----- |
| AC-01 | No play history returns empty seeds                      | API   |
| AC-02 | Unknown user returns empty seeds                         | API   |
| AC-03 | Affinity tracks generate non-empty seeds                 | API   |
| AC-04 | Fallback to play_state when insufficient affinity tracks | API   |
| AC-05 | Max 1 seed per album (deduplication)                     | API   |
| AC-06 | Discovery tracks from unplayed library included          | API   |
| AC-07 | Seed cards displayed on home page                        | E2E   |

---

## FEAT-SCROBBLE: Play Tracking

Scrobble logic based on playback duration thresholds.

| AC    | Criterion                                 | Layer |
| ----- | ----------------------------------------- | ----- |
| AC-01 | >50% of track duration marks as played    | API   |
| AC-02 | >240s of long track marks as played (cap) | API   |
| AC-03 | Short tracks (<30s) never scrobble        | API   |
| AC-04 | 95% playback marks as completed           | API   |
| AC-05 | <50% playback marks as skipped            | API   |

---

## FEAT-SIGNALS: Signal Persistence

Listening session signal tracking for queue management.

| AC    | Criterion                                        | Layer |
| ----- | ------------------------------------------------ | ----- |
| AC-01 | PLAY_START signal persisted with correct fields  | API   |
| AC-02 | Queue low detected when entries below threshold  | API   |
| AC-03 | Skip pattern detected from recent signal history | API   |

---

## FEAT-NAV: Navigation & Routing

Application routing, deep linking, error pages.

| AC    | Criterion                                    | Layer |
| ----- | -------------------------------------------- | ----- |
| AC-01 | Deep link to album page works after auth     | E2E   |
| AC-02 | Deep link to artist page works after auth    | E2E   |
| AC-03 | 404 page for invalid routes                  | E2E   |
| AC-04 | Browser back/forward navigation works        | E2E   |
| AC-05 | Artist list displays and navigates to detail | E2E   |

---

## FEAT-LYRICS: Lyrics Display

Synchronized lyrics overlay for current track.

| AC    | Criterion                                         | Layer |
| ----- | ------------------------------------------------- | ----- |
| AC-01 | Lyrics overlay opens and closes                   | E2E   |
| AC-02 | Escape key closes overlay                         | E2E   |
| AC-03 | Lyrics content displayed or no-lyrics state shown | E2E   |
| AC-04 | Loading state during lyrics fetch                 | E2E   |

---

## FEAT-SLEEP: Sleep Timer

Timed playback stop.

| AC    | Criterion                            | Layer |
| ----- | ------------------------------------ | ----- |
| AC-01 | Sleep timer modal opens              | E2E   |
| AC-02 | Preset timer activates (e.g. 15 min) | E2E   |
| AC-03 | Remaining time displayed             | E2E   |
| AC-04 | Timer cancellation stops countdown   | E2E   |
| AC-05 | Playback continues while timer runs  | E2E   |

---

## FEAT-A11Y: Accessibility

WCAG compliance across all pages.

| AC    | Criterion                        | Layer |
| ----- | -------------------------------- | ----- |
| AC-01 | Login page passes WCAG 2.1 AA    | E2E   |
| AC-02 | Home page passes WCAG 2.1 AA     | E2E   |
| AC-03 | Albums page passes WCAG 2.1 AA   | E2E   |
| AC-04 | Artists page passes WCAG 2.1 AA  | E2E   |
| AC-05 | Settings page passes WCAG 2.1 AA | E2E   |

---

## FEAT-CHAOS: Resilience / Chaos Testing

Monkey testing — random inputs, viewport changes, API abuse.

| AC    | Criterion                                               | Layer |
| ----- | ------------------------------------------------------- | ----- |
| AC-01 | 1000 random interactions produce no unhandled JS errors | E2E   |
| AC-02 | Malformed API requests don't crash server               | E2E   |
| AC-03 | App recovers from random navigation chaos               | E2E   |

---

## FEAT-EMBEDDINGS: Track Feature Embeddings

MERT/CLAP embedding storage and affinity queries.

| AC    | Criterion                                         | Layer |
| ----- | ------------------------------------------------- | ----- |
| AC-01 | Affinity query returns tracks ordered by score    | API   |
| AC-02 | Affinity query excludes tracks without embeddings | API   |
| AC-03 | Unknown user returns empty affinity results       | API   |
