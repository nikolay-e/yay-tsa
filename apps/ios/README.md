# yay-tsa iOS

Native SwiftUI client for the [yay-tsa-v2](https://github.com/nikolay-e/yay-tsa) music server.
Talks to the server's Jellyfin-compatible REST API (`adapter-jellyfin`) directly — no WebView, no wrapper.

## MVP scope

- Sign in (server address + username/password) against `POST /Users/AuthenticateByName`
- Browse albums, open an album to see its tracks
- Play a track (direct-play stream, no transcoding), play/pause, seek, skip next/previous within the album

Not yet implemented (see `yay-tsa-v2/CLAUDE.md` in the main repo for the full backend feature set):
playback session reporting to the server (`/Sessions/Playing*`), cross-device sync via SSE, favorites,
playlists, artists browsing, adaptive queue / LLM-DJ, search.

## Setup

This project is generated with [XcodeGen](https://github.com/yonaskolb/XcodeGen) from `project.yml` —
the `.xcodeproj` itself is not checked in.

```bash
brew install xcodegen
cd yaytsa-ios
xcodegen generate
open YayTsa.xcodeproj
```

Then in Xcode: select a Signing Team on the `YayTsa` target (Signing & Capabilities) and run.

Re-run `xcodegen generate` any time `project.yml` changes or new source files are added.

## Pointing at a server

On first launch, enter the server address (e.g. `http://192.168.1.10:8080` for a LAN dev box running
`yay-tsa-v2`'s `docker-compose.yml`). Plain `http://` works out of the box — Local Network / ATS exceptions
are enabled in `project.yml` for dev convenience; tighten `NSAppTransportSecurity` before shipping.

## Project layout

```
Sources/
  YayTsaApp.swift        — app entry, wires SessionStore/APIClient/PlayerViewModel
  Root/RootView.swift     — switches between LoginView and the main library view
  Networking/             — APIClient (Jellyfin-flavored REST calls), SessionStore, KeychainStore
  Models/                 — Codable DTOs matching adapter-jellyfin's JSON shapes
  Features/Login/         — sign-in screen
  Features/Library/       — albums list, album detail (tracks)
  Features/Player/        — AVPlayer-backed PlayerViewModel, mini player, now-playing sheet
  Shared/                 — ArtworkView (async album art with placeholder)
```

## Known API quirks (from reading the Kotlin source, not official Jellyfin docs)

- Auth token is opaque (not JWT), accepted as `Authorization: Bearer <token>`, `?api_key=`, or `X-Emby-Token`.
- Ticks are 100ns units: `ticks / 10_000_000 = seconds` (see `BaseItem.runTimeSeconds`).
- No transcoding — streaming always direct-plays the source file at `/Audio/{itemId}/stream`.
- `/Items` has no explicit "sort by track number" option; track order is sorted client-side by
  `(parentIndexNumber, indexNumber)` in `AlbumDetailViewModel`.
