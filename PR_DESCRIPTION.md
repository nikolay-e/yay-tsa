# PR: Track Upload, Metadata Enrichment, and Player UX Improvements

> **Note:** This file is a draft PR description — copy it when creating the PR, then delete the file.

---

## Summary

- Add **track upload** via drag-and-drop with automatic metadata enrichment from 5 providers (MusicBrainz, iTunes, Genius, Last.fm, Spotify)
- Add **runtime API key configuration** — admins can set provider keys from the Settings page without restarting the server; keys are stored in the database (not only in env vars)
- Add **Lyrics View** — full-screen overlay with synced LRC highlighting and auto-scroll
- Add **Sleep Timer** — stops playback after a countdown (15/30/45/60 min)
- Add **Karaoke vocal volume** slider in the player bar
- Fix **mobile player layout** — track info above progress bar, playback controls centered, nav bar always visible below the player
- Fix **login form blocking** after a failed login attempt
- Fix **API key corruption** when clicking Save without changing provider key fields

## What Changed

### Backend
- `POST /tracks/upload` — upload audio files (MP3, FLAC, M4A, OGG) into a Docker-managed library volume; Chromaprint fingerprint deduplication; automatic Artist/Album hierarchy creation
- Metadata enrichment pipeline: MusicBrainz + iTunes (always on) + Genius/Last.fm/Spotify (require keys); parallel execution via Java 21 virtual threads; 7-day cache
- `GET/PUT /Admin/Settings/metadata` — admin-only endpoint to configure provider API keys at runtime; keys stored in `app_settings` table; DB values take priority over env vars
- `GET/PUT /Admin/Settings/services` — configure Audio Separator and Lyrics Fetcher service URLs
- `LrcLibClient` — lrclib.net as primary lyrics source
- DB migrations V11 (fingerprint column) and V12 (app_settings table, albums.is_complete)
- Removed unused `LyricsFetcherClient`, `YtDlpDownloader`, `UploadUrlRequest`

### Frontend
- Track Upload Dialog with per-file progress and duplicate feedback
- Settings: Metadata Providers section (Genius/Last.fm/Spotify keys); fields never pre-fill with masked server values — only non-empty submissions are sent
- Settings: Service URLs section (Audio Separator, Lyrics Fetcher)
- Lyrics View (full-screen overlay, LRC sync, auto-scroll)
- Sleep Timer modal with badge showing minutes remaining
- Karaoke vocal volume slider
- Mobile player: track info row above progress bar; centered playback controls; `bottom-above-tab-bar` utility so nav is always visible below the player
- Library cache invalidation after track upload (no page reload needed)

### Infrastructure
- `uploads_data` named Docker volume for uploaded tracks
- `LIBRARY_ROOTS=/media,/app/uploads` scanned by backend
- `vite.config.ts` added to frontend bind mounts

## Security

- API key fields in Settings never pre-fill with masked values; empty submission preserves existing keys server-side (both frontend and backend enforce this)
- `AppSettingsController` PUT skips blank values
- Removed `GenerateHash.java` (utility with hardcoded password)
- Added `test-*.sh`, `GenerateHash.java`, `services/server/temp-build/` to `.gitignore`

## Test Plan

- [ ] `docker compose up` starts cleanly; all services become healthy
- [ ] Upload an MP3 track → appears in Songs/Albums/Artists without page reload
- [ ] Upload duplicate track → 409 Conflict, no duplicate in library
- [ ] Settings → enter Genius token → Save → reload page → field shows "✓ Configured"; existing token not corrupted
- [ ] Settings → click Save with empty fields → no change to stored keys
- [ ] Lyrics button in player bar → lyrics overlay opens with synced highlighting during playback
- [ ] Sleep Timer → set 15 min → badge shows countdown → playback stops at zero
- [ ] Mobile (<768px): track info above progress bar, playback controls centered, nav bar visible below player
- [ ] Login with wrong password → error shown, form stays active for retry (no page reload needed)
- [ ] MusicBrainz and iTunes work without API keys (check backend logs for provider calls)
