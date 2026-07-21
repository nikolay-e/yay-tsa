# yay-tsa iOS

> Extends [../../CLAUDE.md](../../CLAUDE.md) — inherits workspace conventions (integration tests only, no docstrings, Russian communication)

Native SwiftUI client for the yay-tsa music server. Talks to the v2 backend's
Jellyfin-compatible REST API (`adapter-jellyfin`) directly — no WebView, no wrapper, no backend
changes required.

## Feature surface

Full parity with `apps/web` (PWA): browse albums/artists/playlists/favorites/audiobooks, playback
with session reporting (`/Sessions/Playing*`), text + semantic search, synced LRC lyrics, offline
downloads, device remote control/transfer. Native extras: Adaptive DJ / Radio sessions, Group
Listen (SSE-synced multi-device playback with clock/drift correction), karaoke (discrete stems +
continuous vocal-blend dual-player), admin panel (users, library scan, ReplayGain, cache).

## Build & run

```bash
bash setup-mac.sh        # Homebrew + XcodeGen + generates YayTsa.xcodeproj
open YayTsa.xcodeproj    # select a Signing Team, pick a device, Cmd+R
```

The `.xcodeproj` is generated from `project.yml` (XcodeGen) and not committed — re-run
`xcodegen generate` whenever `project.yml` changes or files are added. Signing needs a human once
(Xcode > Settings > Accounts; a free personal team works for a device you own).

UI tests: `YayTsaUITests` scheme (XCUITest against a live backend at `http://localhost:8080`).

## Backend API notes (verified against the Kotlin controllers, not generic Jellyfin docs)

- **Auth**: `POST /Users/AuthenticateByName` `{"Username", "Pw"}` → `AccessToken` (opaque) +
  `User.Id`. Send `Authorization: Bearer <token>`; `?api_key=` only for URLs that can't set
  headers (audio/image src).
- **Browse**: `GET /Items?...` → `{"Items", "TotalRecordCount", "StartIndex"}`. `RunTimeTicks`
  is 100ns units (`seconds = ticks / 10_000_000`). No server-side track-number sort — the app
  sorts client-side by `(ParentIndexNumber, IndexNumber)`.
- **Streaming**: `GET /Audio/{itemId}/stream?api_key=<token>` — direct-play only, no transcoding;
  don't add `container`/`audioCodec` params.
- **SSE**: `GET /v1/me/devices/events` (Server-Sent Events, not WebSocket).
- If anything here disagrees with the running server, the Kotlin source
  (`yay-tsa-v2/adapter-jellyfin/`) is the ground truth.

## Layout

```
project.yml                — XcodeGen spec (ATS open for plain-HTTP LAN servers; tighten before App Store)
setup-mac.sh               — bootstrap on a fresh Mac
Sources/
  YayTsaApp.swift          — entry; wires SessionStore/APIClient/Player/DeviceEvents/Downloads/Radio/GroupSync
  Root/RootView.swift      — login vs. main tabs via SessionStore.isAuthenticated
  Networking/              — APIClient (all HTTP), SessionStore (UserDefaults + Keychain token),
                             DeviceEventsClient (SSE + heartbeat), DownloadManager, DeviceIdentity
  Models/                  — Codable DTOs matching the wire shapes exactly (see CodingKeys)
  Features/                — Login, Home, Library, Artists, Playlists, Favorites, Audiobooks,
                             Search, Player (incl. karaoke/lyrics/queue), Radio, GroupListen,
                             Devices, Settings (incl. DJ preferences, admin users)
  Shared/                  — ArtworkView, buttons, Theme, toasts
UITests/                   — XCUITest end-to-end flows
```
