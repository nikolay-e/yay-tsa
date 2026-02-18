# Changelog

All notable changes to this project are documented here.

## [Unreleased] — feat/track-upload-and-metadata

### Added

#### Backend
- **Track Upload API** (`POST /tracks/upload`) — upload audio files (MP3, FLAC, M4A, OGG) directly into the Docker-managed library volume; deduplication via Chromaprint fingerprint and artist+title fallback; 409 Conflict on exact duplicate
- **Audio Fingerprinting** — Chromaprint-based fingerprint extraction stored in `audio_tracks.fingerprint`; V11 migration adds the column and index
- **Metadata Providers** — aggregated metadata enrichment pipeline at upload time:
  - **MusicBrainz** — always enabled, no API key required
  - **iTunes Search** — always enabled, no API key required (artwork up to 600×600)
  - **Genius** — enabled when Genius Access Token is configured
  - **Last.fm** — enabled when Last.fm API Key is configured
  - **Spotify** — enabled when Spotify Client ID + Secret are configured
- **AppSettings API** (admin only) — `GET/PUT /Admin/Settings/metadata` and `GET/PUT /Admin/Settings/services` to configure provider keys and service URLs at runtime without restarting; keys stored in the new `app_settings` DB table (V12 migration); DB values take priority over environment variables
- **iTunes Search Provider** (`ItunesSearchProvider.java`) — fetches album name, year, artwork (600×600), and genre from the iTunes Search API; priority 65; no rate limit; 8 s timeout
- **LrcLib Client** (`LrcLibClient.java`) — fetches synced/plain lyrics from lrclib.net as primary lyrics source
- **Library Roots Config** (`LibraryRootsConfig.java`) — resolves multiple `LIBRARY_ROOTS` and `UPLOAD_LIBRARY_ROOT` paths from environment variables

#### Frontend
- **Track Upload Dialog** — drag-and-drop or file picker for uploading audio files; per-file progress; metadata preview; duplicate detection feedback
- **Metadata Provider Settings** (admin only) — new Settings section to enter/update API keys for Genius, Last.fm, and Spotify; fields show "✓ Configured" when a key is already stored and left blank to keep existing value
- **Service URL Settings** (admin only) — configure Audio Separator and Lyrics Fetcher service URLs at runtime
- **Lyrics View** — full-screen overlay for synced (LRC) and plain text lyrics, with real-time highlighting and auto-scroll during playback
- **Karaoke Vocal Volume** — slider in the player bar to blend between vocal and instrumental tracks in karaoke mode
- **Sleep Timer** — set a countdown (15/30/45/60 min or custom) after which playback stops; badge on the timer button shows minutes remaining
- **Mobile Player Layout** — track info (artwork + title + artist) moved above the progress bar on small screens; playback controls centered; desktop layout unchanged
- **Library Cache Invalidation** — after successful track upload, Songs/Albums/Artists tabs refresh immediately without a page reload

#### Infrastructure
- `uploads_data` named Docker volume for uploaded tracks (writable by backend and audio-separator)
- `LIBRARY_ROOTS=/media,/app/uploads` — backend scans both the read-only media mount and the writable uploads volume
- `vite.config.ts` added to frontend bind mounts so config changes are picked up without rebuilding the image

### Fixed

- **Login form blocking** — after a failed login (wrong password) the auth callback was incorrectly set before the request, causing a spurious `logout()` call that left the form stuck in a loading state; callback is now registered only after a successful response
- **Mobile player overlapping navigation bar** — player bar is now positioned above the tab bar using `calc(tab-height + safe-area-inset-bottom)` instead of at `bottom: 0`; the navigation bar is always visible below the player
- **API key corruption on save** — the Settings page previously pre-filled key fields with masked values (e.g. `V1Ck****tv4M`); clicking Save without editing would overwrite the real token with the masked string; fields are now always empty on load and only non-empty values are sent in the PUT request
- AppSettingsController PUT now skips blank values, so a "Save" with an empty field preserves the existing key in the database

### Changed

- Auth error callback in `auth.store.ts` is registered after successful login, not before, preventing false logout on credential errors
- `--spacing-player-bar-mobile` increased from 64 px → 112 px to account for the new mobile info row above the progress bar

### Security

- Removed `GenerateHash.java` (local utility with hardcoded password, should never have been in the working tree)
- Added `test-*.sh`, `GenerateHash.java`, and `services/server/temp-build/` to `.gitignore`
- API key fields in Settings no longer pre-fill with masked backend values; empty submission preserves existing keys server-side

### Database Migrations

| Version | Description |
|---------|-------------|
| V11 | Add `fingerprint TEXT` column and index to `audio_tracks` |
| V12 | Add `app_settings (key, value, updated_at)` table; add `is_complete BOOLEAN` to `albums` |

### API Endpoints Added

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/tracks/upload` | User | Upload audio file |
| GET | `/Admin/Settings/metadata` | Admin | Read metadata provider keys (masked) |
| PUT | `/Admin/Settings/metadata` | Admin | Update metadata provider keys |
| GET | `/Admin/Settings/services` | Admin | Read service URLs |
| PUT | `/Admin/Settings/services` | Admin | Update service URLs |
