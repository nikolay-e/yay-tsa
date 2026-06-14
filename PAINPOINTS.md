# PAINPOINTS — Competitor & User-Complaint Research

Append-only cumulative log. Each dated section records newly-found pain from competing self-hosted music servers/clients, mapped against yay-tsa's positioning. Every finding carries a quote + URL. Ranked by frequency × severity:

- 🔴 Dealbreaker — users abandon a competitor over this
- 🟡 Recurring friction — echoed across sources, drives the "X annoyances" threads
- 🔵 Wish / nice-to-have — frequently requested, not a reason anyone leaves

Category: self-hosted music servers/clients. Competitors surveyed: **Navidrome, Plex/Plexamp, Jellyfin (as music server), Finamp, Symfonium, Feishin** (plus Ampache, Gonic, Amperfy, LMS where they corroborate a category-wide gap).

> "Our status" is **inferred from `CLAUDE.md` architecture**, not verified against source (this run was web-research-only by request). Treat status as a hypothesis to confirm in code.

---

## 2026-06-14 — Initial sweep (6 web-research scouts: 4 per-competitor + category-wide + forums)

### 🔴 Dealbreakers (frequency × severity highest)

**1. Discovery / recommendations — "the Spotify gap." CATEGORY-WIDE. The single most-repeated complaint in the entire category.**

- _"The biggest problem people hear from those wanting to move away from Spotify… is almost always the exact same issue: discoverability and recommendations."_ — xda-developers
- _"Everything else about self-hosting music is solved—library management, metadata, multi-device playback—but discovery has always been the weak link."_ — xda-developers
- _"nothing currently beats Plexamp in terms of… 'sonically similar' tracks/artists… artist/track radio… 'library radio': smart shuffling… artist mix builder."_ — HN id=30533087
- Navidrome only shipped a recommendation engine (Instant Mix via Last.fm/Deezer) in **0.60, Feb 2026** — a "finally added" signal after years of absence; and it depends on external services rather than local audio analysis.
- URLs: <https://www.xda-developers.com/stopped-using-spotify-built-own-self-hosted-music-server/> · <https://news.ycombinator.com/item?id=30533087> · <https://news.ycombinator.com/item?id=43711706> · <https://alternativeto.net/news/2026/2/navidrome-0-60-adds-webassembly-based-plugin-system-new-instant-mix-feature-and-more>
- Frequency: 5+ independent sources, the defining axis the whole category is judged on. Recency: all 2026.
- **Our status (inferred): STRONG FIT** — yay-tsa has an `ml` context (CLAP/MERT/Discogs/MusicNN embeddings, HNSW similarity) + `adaptive`/DJ via Claude. This is the category's #1 gap and yay-tsa is architecturally positioned to own it natively (local audio analysis, not just Last.fm lookups). **Highest-leverage differentiator — make sure it actually ships and is discoverable.**

**2. Cross-device / cross-server state fragmentation. CATEGORY-WIDE.**

- _"Play counts, ratings, and playlists don't transfer between Navidrome and Jellyfin. This is an important limitation if you're considering switching."_ — selfhosting.sh
- An entire band-aid tool ecosystem (Maloja, multi-scrobbler, Scrob, RompR sync docs) exists _only_ to patch this; XDA literally published "one app to connect Plex, Jellyfin, and Navidrome — the way they should have from the start."
- URLs: <https://selfhosting.sh/compare/navidrome-vs-jellyfin/> · <https://www.xda-developers.com/single-music-app-plex-jellyfin-navidrome/>
- Frequency: 4 sources + a whole tool category. Category-wide.
- **Our status (inferred): STRONG FIT** — yay-tsa's core thesis is _one authoritative state across Jellyfin/Subsonic/MPD/MCP_ with single-writer device leases and resume-position projection. This directly attacks the complaint that spawned the band-aid ecosystem. Second-highest differentiator.

**3. Plex pricing-trust collapse + cloud-account dependency on a "self-hosted" server. PRODUCT (Plex/Plexamp) — but it's the #1 reason people leave for true self-hosting.**

- Lifetime Plex Pass: _"$119.99 → $249.99 (Mar 2025) → $749.99 (effective Jul 1 2026)"_; poll: _"only 1% would accept $750."_ Remote streaming _"quietly moved behind a paywall, after it had been free throughout the platform's history."_
- _"Plex requires authentication through plex.tv servers. If Plex's auth servers go down (this has happened), you can't access your own music."_
- URLs: <https://www.androidauthority.com/plex-pass-750-dollars-not-worth-it-why-3669212/> · <https://9to5mac.com/2025/11/27/plex-paywall-for-remote-streaming-now-being-enforced/> · <https://dev.to/selfhostingsh/navidrome-vs-plex-for-music-which-should-you-self-host-jdp>
- Frequency: dominant, freshest theme of 2026 (two hikes in 14 months). Recurring news cycle.
- **Our status (inferred): STRONG FIT by construction** — free, self-hosted, opaque device-bound tokens validated locally (no external auth round-trip), no paywall. This is a positioning win to lean into, not a code gap.

**4. Finamp offline/download subsystem: data loss, freeze-until-reinstall, crashes at scale. PRODUCT (Finamp) — the live rage-quit for mobile users.**

- _"All of my music has been deleted"_ after logout/login despite the prompt saying it wouldn't delete; logs confirm `All deletes complete.` (server had been offline for months → mass deletion). Issue #1290, Jul 2025.
- Library of 90+ GB / 5,173 items _"causes the app to freeze in offline mode… makes playback impossible until reinstalling the app"_; pseudo-playlist _"fails to load with 2,000+ tracks."_ Issue #832, Aug 2024, still OPEN.
- _"downloading playlists brings the entire app to a grinding halt, and causes crashes."_
- URLs: <https://github.com/jmshrv/finamp/issues/1290> · <https://github.com/jmshrv/finamp/issues/832> · <https://github.com/UnicornsOnLSD/finamp/issues/1462>
- Frequency: 5+ GitHub issues, 2024–Nov 2025, active.
- **Our status (inferred): N/A today** — yay-tsa PWA is NetworkOnly for audio (no offline playback by design). If/when offline lands, treat "bulletproof at 2000+ tracks, never silently delete" as the bar to clear. Backlog note, not current.

### 🟡 Recurring friction

**5. Gapless playback — unsolved or buggy across the board, a "hard must" disqualifier for album listeners.**

- Jellyfin: open since **2018** (issue #106), still client-dependent; comparison tables list it as _"Partial (depends on browser)."_
- Finamp: breaks with transcoding ON (_"audible between any two tracks meant to be gapless,"_ #861) and when _"the screen is off"_ (#998).
- Symfonium: _"audible 'click' and a tiny gap between the tracks"_ — recurring across forum threads **Oct 2022 → May 2026**, including casting/UPnP/Sonos.
- Plexamp: _"downloads not gapless"_ and _"Chromecast — no Gapless"_ (version-specific regressions through Jan 2026).
- Album listener: _"I have a few albums that requires gapless playback and most web players can't accommodate them."_ — HN
- URLs: <https://github.com/jellyfin/jellyfin/issues/106> · <https://github.com/jmshrv/finamp/issues/861> · <https://github.com/jmshrv/finamp/issues/998> · <https://support.symfonium.app/t/issue-with-gapless-playing-using-local-decoder/13831> · <https://forums.plex.tv/t/plexamp-downloads-not-gapless/907776> · <https://news.ycombinator.com/item?id=42513171>
- Frequency: every major client has an open/recurring gapless thread; coupled to transcoding and casting failure modes.
- **Our status (inferred): OPPORTUNITY** — platform layer already has dual audio elements + promise-chain serialization for seamless transitions. Gapless-with-transcoding correct is a credible "we fixed what they couldn't." Verify it actually works across transcode + background.

**6. Scrobbling fragile / breaks repeatedly (Last.fm + ListenBrainz). PRODUCT (Navidrome, Plexamp, Jellyfin) — table-stakes that keeps breaking.**

- Navidrome: _"context deadline exceeded… Could not fetch LastFM session key… Could not validate ListenBrainz token"_ — 6+ distinct bug issues across 0.54→0.58 (early–late 2025); loved/♥ tracks not syncing.
- Plexamp: _"Songs listened to offline… not sent to last.fm after reconnecting"_; _"scrobbles a song I am not playing… every 15 seconds."_
- Jellyfin: plugin-only, server-side events — _"3rd party apps (Gelli, Finamp) may not scrobble"_; _"Only the last-played track is scrobbled."_
- URLs: <https://github.com/navidrome/navidrome/issues/3633> · <https://github.com/navidrome/navidrome/issues/4339> · <https://github.com/FoxxMD/multi-scrobbler/issues/409> · <https://github.com/jesseward/jellyfin-plugin-lastfm/issues/41>
- Frequency: very recurring across all three servers + multiple versions.
- **Our status (inferred): OPPORTUNITY** — yay-tsa's transactional outbox + authoritative playback reporting is the right shape for resilient scrobbling (queue offline events, retry on upstream timeout, dedupe). Confirm scrobble integration exists/is resilient.

**7. No in-app tag / metadata editing — near-universal gap. CATEGORY-WIDE (only Ampache has it).**

- Navidrome maintainer: _"Sorry, but there are no plans to implement tag editing in Navidrome"_ (refused Jul 2024, reaffirmed Dec 2025).
- _"Currently, only Ampache lets you edit tags from a browser. I'm forced to keep Ampache… just for this purpose."_
- Read-only-by-design forces users to external desktop tools (Picard, Mp3tag, beets); renames then _"remove songs from playlists."_
- URLs: <https://github.com/navidrome/navidrome/discussions/3103> · <https://github.com/navidrome/navidrome/discussions/2418> · <https://www.navidrome.org/docs/faq/>
- Frequency: 3+ sources; breaks the "manage from any device" promise. Navidrome explicitly refuses it.
- **Our status (inferred): GAP / backlog candidate** — yay-tsa's scanner writes a read-only `library` schema; in-app tag editing would be a real differentiator vs Navidrome's hard "no." Note: must handle the rename→playlist/favorite severing risk (already a known scanner concern in memory).

**8. "Music is second-class to video" + heavy server for music-only use. PRODUCT (Jellyfin, Plex) — universal listicle frame.**

- Jellyfin: _"music is one feature among many, not the primary focus"_; official app _"functional but video-focused"_ / _"clunky and far from intuitive."_
- Plex: idle RAM _"~300–600 MB vs Navidrome ~50 MB (10x lighter),"_ startup _"15–30 s vs 2–3 s"_ — _"overhead for video transcoding, DLNA, and media analysis is wasted resources."_
- URLs: <https://selfhosting.sh/compare/navidrome-vs-jellyfin/> · <https://www.xda-developers.com/clients-that-make-jellyfin-look-more-like-plex-and-spotify/> · <https://dev.to/selfhostingsh/navidrome-vs-plex-for-music-which-should-you-self-host-jdp>
- Frequency: universal across every comparison.
- **Our status (inferred): STRONG FIT** — yay-tsa is music-only and lightweight by construction. Positioning win.

**9. No official / polished mobile + iOS client; "slickest apps target one backend." CATEGORY-WIDE (esp. iOS + web).**

- Navidrome has _no_ official app — quality depends entirely on which Subsonic client you pick. Finamp is Jellyfin-only + no desktop. Symfonium is the polished one but _Android-only + paid + closed-source._
- _"slickest apps are always targeted to one specific backend."_ — HN. iOS collapses to play:Sub/Amperfy (both with gaps); nobody owns iOS or a polished web player.
- URLs: <https://news.ycombinator.com/item?id=42513171> · <https://en.xiaoz.org/post/20552> · <https://alternativeto.net/software/symfonium/about>
- Frequency: 3+ threads; structural.
- **Our status (inferred): PARTIAL FIT** — yay-tsa ships a cross-platform web PWA (covers iOS + desktop via one polished surface) and is multi-protocol so users aren't locked to one client. The PWA-quality bar is the thing to keep raising.

**10. Multi-artist / album-artist tag handling buggy & regression-prone. PRODUCT (Jellyfin) — but a metadata-correctness lesson.**

- Jellyfin 10.11+ _"randomize[s] which artists are stored… frequently excluding the artist listed in the albumartist field"_ (regression 10.10.7→10.11.1, #15283, Oct 2025); comma-vs-semicolon parsing quirks.
- URLs: <https://github.com/jellyfin/jellyfin/issues/15283> · <https://github.com/jellyfin/jellyfin/issues/11411>
- Frequency: recurring across versions (fixed then regressed).
- **Our status (inferred): watch-item** — yay-tsa's authoritative library/scanner should handle multi-value artist tags deterministically; cite as a correctness target.

**11. Library scan speed / metadata maintenance burden. CATEGORY-WIDE.**

- Jellyfin: _"Very slow initial Library scan… 24 hours or longer"_ on large/NFS libraries; the project itself acknowledged (State-of-the-Fin 2026) _"performance issues caused by client-side enumeration and filtering of large datasets."_
- _"you are now IT and customer service"_ — tagging/remote-access setup is on the user.
- URLs: <https://github.com/jellyfin/jellyfin/issues/2600> · <https://jellyfin.org/posts/state-of-the-fin-2026-01-06/> · <https://www.howtogeek.com/self-hosted-navidrome-server-replace-spotify-works-great/>
- Frequency: long-standing, project-acknowledged.
- **Our status (inferred): watch-item** — yay-tsa targets "thousands of files/min" scan throughput + p95 /Items <150 ms on 50k tracks (per CLAUDE.md). Keep those targets honest; large-library /Items p95 is the exact place Jellyfin is bleeding.

### 🔵 Wishes / backlog candidates

**12. Smart / auto-continuing playlists & radio-when-queue-ends.**

- Navidrome smart playlists are _JSON-file-only, beta, no UI editor_ (_"User interface for editing rules (currently JSON-file-only)"_ not implemented, #1417). LMS user: _"sorely missing… smart playlist feature that automatically kicks in once my current queue has ended."_ Jellyfin: native smart playlists require a plugin.
- URLs: <https://github.com/navidrome/navidrome/issues/1417> · <https://forums.lyrion.org>
- **Our status (inferred): FIT** — overlaps with the `adaptive` queue / DJ. Auto-continue radio when the queue drains is a concrete, high-want feature.

**13. Podcasts / audiobooks not first-class. CATEGORY-WIDE.**

- Navidrome podcast request open **5+ years** (#793, Feb 2021, ~39 reactions, still open); audiobook request (#1419) closed as stale. Users pushed to Audiobookshelf. Jellyfin has no RSS podcast fetcher.
- URLs: <https://github.com/navidrome/navidrome/issues/793> · <https://github.com/navidrome/navidrome/issues/1419>
- **Our status (inferred): PARTIAL** — yay-tsa already has an audiobooks feature (genre=Audiobook marker + resume_position projection, per memory). Podcasts/RSS remain a gap if in scope.

**14. Lyrics (synced .lrc) — client-dependent and unreliable.**

- Navidrome: _"No LRC file support"_; _".lrc lyrics not displayed despite correct config."_ Finamp: no offline lyrics, weak lyrics UI (_"lyrics full screen like Spotify"_ requested). Comparison tables: Lyrics _"client-dependent" / "limited."_
- URLs: <https://github.com/navidrome/navidrome/issues/5531> · <https://github.com/jmshrv/finamp/issues/731>
- **Our status (inferred): backlog** — synced lyrics in the PWA is a recurring want; server-guaranteed (not client-dependent) lyrics is the differentiator angle.

**15. Feishin single-maintainer fragility / direction whiplash (NOT abandoned — correction).**

- Contrary to a common assumption, Feishin is **actively maintained** (v1.13.0, Jun 2026). But the Rust rewrite (`audioling`) was cancelled and folded back: _"Tauri/rust did not work out… I'm just going through Feishin piece by piece to modernize it… for when Navidrome releases its stable API"_ — with _"new feature requests will not be accepted"_ during modernization. Single-maintainer, stop-start direction.
- URL: <https://github.com/jeffvli/feishin/discussions/947>
- **Our status:** context, not a yay-tsa gap — illustrates the maintenance fragility of the web-client niche yay-tsa's PWA competes in.

---

### Synthesis — where yay-tsa's bets line up with the category's pain

The two highest-frequency, category-wide dealbreakers map **directly** onto yay-tsa's existing architecture:

1. **Native discovery/recommendations** (#1) — yay-tsa's `ml` + `adaptive` contexts are exactly the thing the entire category is judged on and almost nobody ships natively. Make it real and discoverable.
2. **Single authoritative cross-protocol state** (#2) — yay-tsa's core thesis kills the fragmentation complaint that spawned an entire band-aid tool ecosystem.

Free/self-hosted/local-auth (#3) and music-only/lightweight (#8) are positioning wins yay-tsa already holds vs Plex. The biggest _correctness_ targets to not fumble: **gapless-with-transcoding** (#5, everyone's open bug), **resilient scrobbling** (#6, breaks constantly), **multi-artist metadata** (#10), and **large-library /Items p95** (#11). The biggest _net-new_ differentiator vs the field would be **in-app tag editing** (#7) — the one thing Navidrome flatly refuses.

Evidence caveat: GitHub reaction tallies were not always fetchable; frequency for some GitHub items is inferred from open/recurring status + dates rather than vote counts. Reddit thread bodies were not directly retrievable — forum-voice quotes come from HN, GitHub issues, and first-person migration blogs. Thematic rankings are corroborated across multiple independent sources.

---

## 2026-06-14 — Master deduplicated table (5-scout dedupe+rank synthesis)

Single deduplicated, frequency×severity-ranked master list across all five scouts.
Duplicates merged ruthlessly — value is the cross-source RECURRING pain. Each row keeps
the single best verbatim quote + URL, the competitor(s), and a frequency signal.
"Category-wide" = afflicts most/all products; "product-specific" = scoped to one.

Legend: 🔴 dealbreaker · 🟡 recurring friction · 🔵 wish.

### 🔴 Dealbreakers

| #   | Painpoint                                                                                                                                                          | Best verbatim quote                                                | Source                                                                                                       | Competitor(s)               | Frequency signal                                                                                                                                                                                                                                                         | Scope                                              |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------ | --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------- |
| 1   | **Discovery / recommendations / radio is THE gap** — no "discover weekly", radio bounded to owned library ("closed-library trap"); users re-subscribe to streaming | "So fuck it, I subscribed to Tidal"                                | [lemmy.world/post/20546943](https://lemmy.world/post/20546943) (228 upvotes)                                 | Navidrome, Jellyfin, all    | Strongest cross-scout consensus: 228 upvotes; HN 46517636 ("the thing I miss most is music discovery via radios/discover weekly"); Navidrome discussions/4332 ("library without a librarian"); octo "closed-library trap"; 7+ band-aid tools (Explo 1.6k★, AudioMuse-AI) | Category-wide                                      |
| 2   | **Offline download/sync breaks at scale + data loss + no bulk/delta-sync**                                                                                         | "All of my music has been deleted"                                 | [github.com/jmshrv/finamp/issues/1290](https://github.com/jmshrv/finamp/issues/1290)                         | Finamp, Plexamp, Feishin    | Feishin offline #47 = 110 reactions (biggest single gap); finamp #832 (2000+ freeze), #1462 (153/900); Plex t/861159 ("all playlists evaporate" offline)                                                                                                                 | Category-wide                                      |
| 3   | **Multi-device handoff / queue sync / "continue where I left off"** — universally requested, universally unbuilt                                                   | "surprisingly annoying to switch clients with it missing"          | [support.symfonium.app/t/9390](https://support.symfonium.app/t/9390)                                         | Symfonium, Plex, Finamp     | plex t/936496 ("will not sync where left off"); finamp remote-control #24 (57 reactions)                                                                                                                                                                                 | Category-wide                                      |
| 4   | **Gapless playback broken or missing**                                                                                                                             | "requesting this for years and getting ignored"                    | [github.com/jellyfin/jellyfin/issues/106](https://github.com/jellyfin/jellyfin/issues/106) (open since 2018) | Jellyfin, Finamp, Navidrome | Jellyfin #106 (7yr open); Finamp screen-off click #998; Navidrome web #745 (closed not-planned)                                                                                                                                                                          | Category-wide                                      |
| 5   | **Scrobbling correctness** — duplicates, offline-loss, Subsonic plays never reach target, time-cutoff drops                                                        | "scrobbles a song I am not playing every 15 seconds"               | [forums.plex.tv/t/910447](https://forums.plex.tv/t/910447)                                                   | Plex, Navidrome, Jellyfin   | multi-scrobbler #430 (time-cutoff drops); navidrome #1882 (Subsonic→ListenBrainz never arrives); jellyfin double-stop dups                                                                                                                                               | Category-wide                                      |
| 6   | **Metadata mishandling** — multi-artist/multi-genre collapse, album-splitting, external fetch overwrites local                                                     | "only the first genre is sent to clients"                          | [github.com/navidrome/navidrome (PR 1251)](https://github.com/navidrome/navidrome/pull/1251)                 | Navidrome, Jellyfin         | navidrome #2728 (album split); jellyfin #14622 ("feat. not split — regression in 10.10"); MusicBrainz fetch "butchered" names; navidrome #3893 (ignores local artist.jpg)                                                                                                | Category-wide                                      |
| 7   | **Plex cloud-tether + escalating paywall** ($119→$249→$749)                                                                                                        | "shouldn't use a self-hosted service that insists on phoning home" | [news.ycombinator.com/item?id=45174707](https://news.ycombinator.com/item?id=45174707)                       | Plex/Plexamp                | Dominant 2026 news theme                                                                                                                                                                                                                                                 | Product-specific (yay-tsa sidesteps — positioning) |
| 8   | **Mobile client fragmentation / no great first-party app (esp. iOS)**                                                                                              | "the slickest apps always targeted to one backend"                 | [news.ycombinator.com/item?id=42513171](https://news.ycombinator.com/item?id=42513171)                       | Navidrome, Jellyfin         | navidrome #2439 ("no plans for official client"); XDA "Jellyfin music playback broken for years"                                                                                                                                                                         | Category-wide                                      |
| 9   | **Stream-auth + transcode silent failure** — loads metadata+art, plays nothing                                                                                     | "doesn't trigger transcode server-side"                            | [apps.apple.com/app/id1530145038](https://apps.apple.com/app/id1530145038)                                   | Amperfy, Airsonic-Advanced  | airsonic-advanced #626                                                                                                                                                                                                                                                   | Category-wide                                      |
| 10  | **Background playback stops + battery drain retrying unreachable server**                                                                                          | "40-60% battery drain"                                             | [forums.plex.tv/t/818072](https://forums.plex.tv/t/818072)                                                   | Plexamp, Finamp             | finamp #956 (49 comments)                                                                                                                                                                                                                                                | Category-wide                                      |

### 🟡 Recurring Friction

| #   | Painpoint                                                                            | Best quote / signal         | Source                                                                                | Competitor(s)               | Scope            |
| --- | ------------------------------------------------------------------------------------ | --------------------------- | ------------------------------------------------------------------------------------- | --------------------------- | ---------------- |
| 11  | **ReplayGain inconsistent across entry paths** (radio vs manual; MPD ignores/random) | "radio louder than manual"  | [navidrome #4299](https://github.com/navidrome/navidrome/issues/4299)                 | Navidrome (also #2308 MPD)  | Category-wide    |
| 12  | **Synced .lrc lyrics ignored in web player**                                         | discussion #5531            | [navidrome #5531](https://github.com/navidrome/navidrome/issues/5531)                 | Navidrome                   | Category-wide    |
| 13  | **Library scan misses new files / needs two scans**                                  | needs 2 scans to detect     | [navidrome #4121](https://github.com/navidrome/navidrome/issues/4121)                 | Navidrome (jellyfin #15304) | Category-wide    |
| 14  | **Android Auto / CarPlay truncates to first letters / no full library**              | truncates, no full library  | [amperfy #243](https://github.com/BLeeEZ/amperfy/issues/243)                          | Amperfy, Jellyfin           | Category-wide    |
| 15  | **Audiobook resume / chapters broken** (>100min lose position)                       | "lose position" past 100min | [jellyfin #5703](https://github.com/jellyfin/jellyfin/issues/5703)                    | Jellyfin                    | Category-wide    |
| 16  | **In-app tag editing refused**                                                       | "no plans"                  | [navidrome discussions/3103](https://github.com/navidrome/navidrome/discussions/3103) | Navidrome                   | Product-specific |
| 17  | **Smart / dynamic playlists awkward**                                                | .nsp files only, no UI      | [navidrome](https://github.com/navidrome/navidrome)                                   | Navidrome                   | Product-specific |
| 18  | **Large-library performance collapse**                                               | perf collapse at scale      | [HN 35462019](https://news.ycombinator.com/item?id=35462019)                          | Category-wide               | Category-wide    |
| 19  | **Search misses accents / non-Latin / no fuzzy**                                     | PR5413 / #255               | [navidrome PR5413](https://github.com/navidrome/navidrome/pull/5413)                  | Navidrome                   | Category-wide    |
| 20  | **Crossfade missing**                                                                | request thread              | [symfonium t/2335](https://support.symfonium.app/t/2335)                              | Symfonium                   | Category-wide    |
| 21  | **Per-network transcoding + no UI confirmation of active profile**                   | t/12943                     | [symfonium t/12943](https://support.symfonium.app/t/12943)                            | Symfonium                   | Product-specific |
| 22  | **Collaborative playlists owner-only**                                               | owner-only edit             | jellyfin forum                                                                        | Jellyfin                    | Category-wide    |
| 23  | **Queue insert-next vs append bugs**                                                 | play:Sub queue bug          | play:Sub                                                                              | Subsonic clients            | Category-wide    |
| 24  | **Electron memory bloat (7GB)**                                                      | "7GB"                       | [feishin discussions/939](https://github.com/jeffvli/feishin/discussions/939)         | Feishin                     | Product-specific |

### 🔵 Wishes

| #   | Painpoint                                                     | Signal                                                        | Source                                                                                | Competitor(s) |
| --- | ------------------------------------------------------------- | ------------------------------------------------------------- | ------------------------------------------------------------------------------------- | ------------- |
| 25  | **Jukebox security** — any user can play to physical speakers | "any user can make music come out of my living room speakers" | [navidrome #2849](https://github.com/navidrome/navidrome/issues/2849)                 | Navidrome     |
| 26  | **Podcasts / RSS support**                                    | discussions/4010                                              | [navidrome discussions/4010](https://github.com/navidrome/navidrome/discussions/4010) | Navidrome     |
| 27  | **Classical / composer handling**                             | tildes thread                                                 | tildes.net                                                                            | Category-wide |
| 28  | **Browse-by-folder**                                          | #661                                                          | [finamp #661](https://github.com/jmshrv/finamp/issues/661)                            | Finamp        |

### Protocol-level constraints (structural causes of the dealbreakers above)

Not user complaints per se, but the **structural causes** behind rows 3/5/6/9 — worth
noting because yay-tsa's "one core, many protocols" design can fix them at the source.

| Constraint                                                 | Source                                                                                              | Relates to                 |
| ---------------------------------------------------------- | --------------------------------------------------------------------------------------------------- | -------------------------- |
| OpenSubsonic: no capability discovery                      | [discussions/113](https://github.com/opensubsonic/open-subsonic-api/discussions/113)                | client feature detection   |
| OpenSubsonic: array tags missing                           | [navidrome PR1251](https://github.com/navidrome/navidrome/pull/1251)                                | row 6 (multi-artist/genre) |
| OpenSubsonic: no total-count pagination                    | [discussions/16](https://github.com/opensubsonic/open-subsonic-api/discussions/16)                  | row 18 (large-library)     |
| OpenSubsonic: no seek-while-transcoding                    | [discussions/21](https://github.com/opensubsonic/open-subsonic-api/discussions/21)                  | row 9 (transcode)          |
| OpenSubsonic: savePlayQueue dup-key                        | gitlab issues/1                                                                                     | row 3 (queue sync)         |
| OpenSubsonic: md5+salt forces plaintext storage            | [discussions/25](https://github.com/opensubsonic/open-subsonic-api/discussions/25) (CVE-2025-27112) | security                   |
| Jellyfin: no server-side queue → shuffle/pagination broken | [jellyfin-vue #1023](https://github.com/jellyfin/jellyfin-vue/issues/1023)                          | rows 3, 4                  |
| Jellyfin: /progress endpoint clobbers favorites/ratings    | [jellyfin #14981](https://github.com/jellyfin/jellyfin/issues/14981) (23 reactions)                 | row 5, data integrity      |

### Top-3 strategic takeaways for yay-tsa

1. **Discovery (row 1) is the single highest-leverage gap** — the #1 reason self-hosters re-pay for streaming (228-upvote "I subscribed to Tidal"). yay-tsa's ML context (CLAP/MERT/Discogs embeddings + Claude adaptive queue) directly targets it natively; clearest competitive moat.
2. **State correctness (rows 3, 5, 6) is yay-tsa's natural advantage** — the single authoritative state engine + OCC + idempotency + device leases structurally fixes queue-sync, scrobble-dedup, and multi-value metadata that every Subsonic/Jellyfin competitor mishandles across protocols.
3. **Offline sync (row 2) is the biggest unbuilt PWA gap** — 110 reactions on one Feishin issue; "all my music deleted" recurs. A PWA can't fully match native offline, but delta-sync + no-silent-delete guarantees would beat incumbents on their worst failure.

## 2026-06-14 — Our-status mapping (Final Verdict; competitor pain mapped to yay-tsa code)

Each competitor painpoint mapped to what yay-tsa ACTUALLY does (read from source; schema-shipped-but-unwired scored NOT-YET, not HAVE).

| Painpoint (competitor pain)                                        | yay-tsa status                                                                                 | Evidence                                                                                                                                                       | Addressability                                                             |
| ------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| Discovery / radio / recommendations                                | **PARTIAL** — ML wired, **LLM-DJ stubbed**, closed-library                                     | HNSW kNN real (`JpaMlQueryPort`); `/recommend/{radio/seeds,daily-mix,discover}` wired; `infra-llm/LlmClient.complete()` returns `null`; semantic-search `TODO` | roadmap (wire local LLM); out-of-library discovery out-of-scope            |
| Offline download/sync at scale + data-loss                         | **PARTIAL** — real IndexedDB offline, gaps at scale                                            | `offline-store.ts`, `audio-offline-sw.js`, LRU eviction, paginated favorite reconcile; non-virtualized `OfflineLibraryPage`                                    | quick-wins: virtualize list, resumable Range download, checksums           |
| Multi-device handoff / queue sync / resume                         | **PARTIAL** — leases+resume+group-sync built; **lease-transfer = 501**                         | `PlaybackHandler` leases, `ResumePosition`, `GroupSyncService` end-to-end; `JellyfinDevicesController:193` transfer NOT_IMPLEMENTED                            | roadmap: implement lease-transfer + solo queue restore                     |
| Gapless + crossfade (screen-off)                                   | **HAVE** (transcoded-gapless partial)                                                          | dual audio elements + WebAudio crossfade + bg-tab `setTimeout` + iOS deferred-ended recovery (`html5-audio.ts`, `player.store.ts`)                             | polish quick-win                                                           |
| Scrobbling correctness + offline timestamps                        | **PARTIAL** — internal correct, **no Last.fm/ListenBrainz**, no idempotency key                | `ScrobbleService` threshold; `ResumePosition` furthest-wins+stale-reject; zero external-scrobble code; `PlayHistoryWritePort` lacks idempotency key            | external scrobble = roadmap (positioning win); idempotency key = quick-win |
| Multi-artist / multi-genre metadata                                | **NOT-YET** — single-value (same trap as classic Subsonic)                                     | `Types.kt` single `albumArtistId` + `genre: String?`; scanner detects `feat./;` then keeps only first                                                          | roadmap (domain → `List<>`)                                                |
| Metadata-enrich overwrites local tags/art                          | **ALREADY-AVOID**                                                                              | enricher writes `cover.jpg` only if missing (`CREATE_NEW`), never edits audio/tags                                                                             | done                                                                       |
| Plex cloud-tether / paywall                                        | **ALREADY-AVOID**                                                                              | self-hosted, own opaque device-bound tokens, no cloud account                                                                                                  | structural advantage                                                       |
| Stream-auth + transcode silent-fail ("art, no audio")              | **MIXED** — auth+Range HAVE, **transcode NOT-YET**                                             | `api_key` auth + 206 Range work; raw-bytes only, `SupportsTranscoding=false`; unsupported codec → silent browser fail                                          | roadmap: on-the-fly transcode fallback (closes the footgun)                |
| Synced `.lrc` lyrics in player                                     | **HAVE** end-to-end                                                                            | `LrclibClient` (synced-first) → `/Lyrics` → `lrc-parser.ts` → `LyricsScroller` scroll+highlight                                                                | done                                                                       |
| Scan misses new files / needs 2 scans                              | **ALREADY-AVOID** — single-pass upsert+reconcile, rename survives                              | `LibraryScanner`; `claimRenamedTrack` preserves entity id on size+mtime match                                                                                  | done (differentiator)                                                      |
| Audiobook resume / "don't auto-complete last 30s"                  | **PARTIAL→HAVE** via merge logic (95% completion + furthest-wins), no literal-30s, no chapters | `audiobook.ts` smart-rewind; `ResumePosition.mergeResume`                                                                                                      | quick-win if literal 30s wanted; M4B chapters = roadmap                    |
| In-app tag editing                                                 | **NOT-YET** — strongest net-new differentiation lane (Navidrome flatly refuses it)             | scanner read-only; no tag-write API anywhere                                                                                                                   | roadmap                                                                    |
| OpenSubsonic gaps (caps advert, arrays, totalCount, savePlayQueue) | **PARTIAL** — mostly NOT-YET                                                                   | extensions list `emptyList()`; single-value artist; no totalCount; no save/getPlayQueue                                                                        | quick-wins: totalCount + real extensions advert                            |
| Jellyfin `/progress` clobbers favorites (data-loss)                | **ALREADY-AVOID** — bounded-context isolation makes it structurally impossible                 | favorites in `core_v2_preferences` vs resume in `core_v2_playback`; progress path never cross-writes                                                           | architectural win                                                          |
| Android Auto / CarPlay full library                                | **NOT-YET** — PWA can't do native car surfaces                                                 | PWA only                                                                                                                                                       | out-of-scope-for-positioning (needs native shell)                          |
| Search accents / Cyrillic / translit / fuzzy                       | **PARTIAL** — pg_trgm + Cyrillic ICU collation HAVE; no `unaccent`/translit                    | `V001` trgm, `LibraryEntityRepository` ICU                                                                                                                     | quick-win: `unaccent`; translit = roadmap                                  |

### Differentiators yay-tsa already holds (competitors can't)

Bounded-context isolation makes the Jellyfin `/progress`-clobbers-favorites data-loss **structurally impossible**; single-pass scan + rename-survival avoids the "scan twice / lost favorites on rename" bug; internal play-timing correctness via externalized `requestTime` + furthest-wins merge; gapless-with-screen-off beyond what most clients attempt; self-hosted no-cloud-tether (anti-Plex).

### Honest gaps yay-tsa shares with (or trails) the field

**No on-the-fly transcoding** (raw-bytes only → the exact "loads art, plays nothing" silent failure on unsupported codecs); **no external scrobbling** + no internal idempotency key (retry can double-count); **single-value metadata** (multi-artist/genre thrown away); **closed-library discovery** + **LLM-DJ stubbed** (the headline "Claude adaptive DJ" currently degrades to ML-only — the pitch overstates reality); **thin OpenSubsonic** (advertises `openSubsonic:true` but empty extensions/no totalCount); **no Android Auto/CarPlay**.

### Highest-leverage backlog (ranked)

1. **Wire a local LLM into the adaptive DJ** — the headline differentiator is currently `null`. (roadmap)
2. **On-the-fly transcode fallback** — closes a silent-playback footgun. (roadmap)
3. **External scrobbling (Last.fm/ListenBrainz) + play-report idempotency key** — chronic field-wide weakness; yay-tsa's outbox+OCC model is purpose-built to do it right. (roadmap + quick-win)
4. **In-app tag editing** — the one thing Navidrome refuses; pairs with multi-value metadata. (roadmap)
5. **Quick-wins:** search `unaccent`; virtualize offline library page; OpenSubsonic `totalCount` + extensions advert; literal last-30s audiobook guard.

Scouts/synthesis: 5/2.
