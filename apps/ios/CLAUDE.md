# yay-tsa iOS — handoff notes for the agent working on this Mac

You are picking this project up on a Mac that a Windows Claude Code session prepared and handed
off via USB flash drive. You have **no memory of the Windows session** — everything you need is in
this file and the rest of the repo. Read this fully before doing anything else.

## What this is

A native SwiftUI iOS client for **yay-tsa** — a self-hosted music server. The server itself
(`yay-tsa-v2`, a Kotlin/Gradle rewrite, hexagonal architecture) lives in a separate repo
(`nikolay-e/yay-tsa` on GitHub) that is NOT present on this machine. This iOS app talks to that
server's Jellyfin-compatible REST API over HTTP — it does not need the server's source code to
build or run, only a running instance to point at.

## Your job, in order

1. **Run `setup-mac.sh`** (`bash setup-mac.sh`) to install Homebrew/XcodeGen and generate the
   `.xcodeproj`. It will stop and tell you if Xcode command-line tools aren't installed yet
   (that one step needs a human to run `xcode-select --install` and click through a GUI installer
   — you can't do it yourself).
2. Signing needs a human once: Xcode > Settings > Accounts (sign in with an Apple ID — a free
   personal team works fine for a device you own), then on the `YayTsa` target's
   Signing & Capabilities tab, pick that team. Tell the user exactly what to click; don't try to
   script around Apple ID login.
3. The user will plug in an iPhone via USB as the physical test device (their role here is the
   same as the Samsung S10 was for VPN testing previously — a real device to validate against,
   not just the simulator). They'll tap "Trust This Computer" on the phone. After that, you can
   select it as the Xcode run destination and build to it directly — no further human input needed
   for that part.
4. **Verify the MVP actually works end-to-end on the device**: sign in against a running
   yay-tsa-v2 backend, browse albums, open an album, play a track, seek, skip next/previous.
   The code was written blind (on a Windows machine with no Xcode) — expect and fix compile
   errors, and actually exercise the golden path on the phone, not just "it builds."
5. Once the MVP is confirmed working, continue with the backend features intentionally left out
   of v1 (see "Known gaps" below) — check with the user on priority before diving into any of them,
   since none were explicitly requested yet.

## Cross-machine workflow

The user also runs a Claude Code session on their Windows PC for this same project. See
**`SYNC.md`** for how the two sessions exchange files (home-server shared folder + optional Remote
Control) — read it before assuming you're the only one touching this code.

## Local-only workflow — do not push

**Nothing gets pushed to any git remote from this project right now.** Commit locally if useful for
your own checkpointing, but do not add a remote, do not push, do not open a PR. This is a deliberate
instruction — treat any git remote operation as needing explicit confirmation first, not just your
own judgment call.

## Permissions

`.claude/settings.json` in this repo already allow-lists routine dev commands (`xcodegen`,
`xcodebuild`, `xcrun simctl`/`xctrace`, read-only `git`/`security`/`codesign`, `swift build/test`,
`brew install/list`) so the user isn't asked to confirm each one. `git push`, `git reset --hard`,
`git clean -f`, and `rm -rf` are explicitly denied at the config level — if you need one of those,
that's a signal to stop and ask rather than route around it.

## Backend API reference (condensed — this is what the app talks to)

No dedicated mobile API exists yet; the app speaks the same Jellyfin-flavored REST API the PWA
uses, exposed by the backend's `adapter-jellyfin` module. All endpoints are at the server root
(no path prefix), e.g. `http://<host>:8080/Users/AuthenticateByName`.

- **Auth**: `POST /Users/AuthenticateByName`, body `{"Username": "...", "Pw": "..."}` → response
  has `AccessToken` (opaque, not JWT) and `User.Id`. Send the token as
  `Authorization: Bearer <token>` on every subsequent request (also accepted as `?api_key=` for
  URLs that can't set headers, e.g. audio/image `<src>`s).
- **Browse**: `GET /Items?IncludeItemTypes=MusicAlbum&Recursive=true` for albums,
  `GET /Items?ParentId=<albumId>&IncludeItemTypes=Audio` for a given album's tracks. Response is
  `{"Items": [...], "TotalRecordCount": N, "StartIndex": N}`. Each item's `RunTimeTicks` is
  100ns units: `seconds = ticks / 10_000_000`. No server-side "sort by track number" option exists
  — the app sorts client-side by `(ParentIndexNumber, IndexNumber)` (disc, then track).
  Images: `GET /Items/{id}/Images/Primary?maxWidth=N&api_key=<token>`.
  Only items with a truthy key in `ImageTags` actually have art.
- **Streaming**: `GET /Audio/{itemId}/stream?api_key=<token>` — direct-play only, no transcoding
  is supported by the server at all, so don't add `container`/`audioCodec` query params (the
  server 415s if they don't match the source file exactly).
- **Playback session reporting** (NOT yet wired up in this app — see gaps below):
  `POST /Sessions/Playing`, `/Sessions/Playing/Progress`, `/Sessions/Playing/Stopped`, bodies like
  `{"ItemId": "...", "PositionTicks": N, "IsPaused": bool}`, all return 204.
- **Real-time cross-device sync** (also not wired up): Server-Sent Events at
  `GET /v1/me/devices/events`, not a WebSocket despite `/ws/**` being present in the security config.

This reference was extracted by having an agent read the actual Kotlin controller source
(`adapter-jellyfin/src/main/kotlin/dev/yaytsa/adapterjellyfin/*.kt`) on `origin/main` of the
`yay-tsa` repo — it is NOT from generic Jellyfin API docs, since this backend only mimics Jellyfin's
shape, not its full behavior. If something here doesn't match what the running server actually
does, the server source is the ground truth, not this file.

## Project layout

```
project.yml               — XcodeGen spec; .xcodeproj is generated, not committed
setup-mac.sh               — run this first
Sources/
  YayTsaApp.swift          — app entry, wires SessionStore/APIClient/PlayerViewModel via @EnvironmentObject
  Root/RootView.swift      — LoginView vs. main library view, based on SessionStore.isAuthenticated
  Networking/
    APIClient.swift        — all HTTP calls
    SessionStore.swift     — serverURL/userId (UserDefaults) + accessToken (Keychain)
    KeychainStore.swift
  Models/                  — Codable structs matching the DTOs above exactly (see CodingKeys)
  Features/Login/          — sign-in screen
  Features/Library/        — albums list, album detail (tracks)
  Features/Player/         — AVPlayer-backed PlayerViewModel, mini player bar, now-playing sheet
  Shared/ArtworkView.swift — AsyncImage wrapper with a placeholder
```

## Known gaps (intentionally out of MVP scope, not oversights)

- No playback session reporting to the server (the server's "one source of truth across devices"
  design point is currently moot for this client — it plays locally but doesn't tell the server)
- No SSE/cross-device sync
- No favorites, playlists, artist browsing, search
- No adaptive queue / LLM-DJ integration (that's exposed via MCP tools server-side, not REST, per
  the backend's architecture manifesto)
- App Transport Security is wide open (`NSAllowsArbitraryLoads: true` in `project.yml`) for dev
  convenience against a plain-HTTP LAN server — tighten this before any App Store submission

## Things to watch for (written blind, unverified)

- `YayTsaApp.swift` wires `SessionStore` and `APIClient` in a custom `init()` and relies on
  `PlayerViewModel`'s default `@StateObject` initializer running independently — double-check this
  actually compiles and wires up in the order expected; it's the one part of the DI setup that
  wasn't triple-checked against the Swift initialization-order rules.
- `AlbumDetailViewModel`'s track sort relies on the stdlib's built-in tuple `<` for `(Int, Int)` —
  confirm no "ambiguous operator" error; if Swift version issues arise, replace with an explicit
  comparator.
