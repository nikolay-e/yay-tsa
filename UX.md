# UX.md — cumulative findings log for /review-ux

## 2026-07-17 — 8008dee1

Scope: apps/web (React PWA) + packages/platform where it affects UX. Method: 6 parallel
scouts (auth/forms, library states, player/queue, playlists/offline/audiobooks, shared
UI/a11y, nav/responsive/PWA) → 2 synthesis (dedupe/rank + adversarial skeptic on the top
10 claims). Skeptic tally: 8 CONFIRMED, 2 PARTIAL, 0 refuted of the top 10; one scout
claim (silent LRU eviction of protected downloads) refuted at synthesis — protected
reasons (manual/favorite/album/playlist) are never evicted (`cache-eviction.ts:20-22`).

### Action list (ranked)

**1. 🔴 DO — Two-step confirm on admin reset-password**
Location: `apps/web/src/features/auth/components/UsersPanel.tsx:209-220`
Evidence: `onClick={() => resetMutation.mutate()}` — no confirm state anywhere in the
reset path, while Delete beside it is two-step (`confirmDelete`, :157, :222-246).
Problem: one stray click on a button adjacent to Delete invalidates another user's
password and sessions. Recommendation: reuse the existing inline two-step confirm with
consequence copy. Effort: Easy. Confidence: high (skeptic-confirmed).
Verification: clicking Reset once does not mutate; second confirming click does.

**2. 🔴 DO — Surface offline-download failures (dead toasts + quota + batch)**
Location: `offline.store.ts:394-442`, `DownloadButton.tsx:84-88`,
`TrackRowMenu.tsx:143-147`, `DownloadTracksButton.tsx:35-40`
Evidence: store `download()` catches, logs, sets `status:'error'`, then **resolves** —
the `.catch(() => toast.add('error', …))` in every caller is dead code; `remove()`
swallows `deleteTrack` failure and deletes state anyway (UI reports success on failure);
`downloadMany(...).catch(() => {})` swallows batch failures; `QuotaExceededError` is
indistinguishable from a network blip. Problem: user boards a plane believing tracks
are offline. Recommendation: rethrow (or return a result) for user-initiated reasons so
callers toast; special-case quota with actionable copy; aggregate failed-count toast for
batches; don't delete state when `deleteTrack` fails. Effort: Medium. Confidence: high
(skeptic-confirmed). Verification: kill network mid-download → error toast appears;
fill quota → distinct "out of space" message.

**3. 🟡 DO — Playlist rename (Jellyfin adapter endpoint + UI)**
Location: `usePlaylists.ts` (no rename mutation), `PlaylistDetailPage.tsx`,
`packages/core/src/api/playlists.service.ts` (no method), backend
`JellyfinPlaylistsController` (no endpoint).
Evidence: backend domain HAS `RenamePlaylist` (`core-domain/playlists/Commands.kt:23`),
wired for OpenSubsonic (`SubsonicPlaylistsController.kt:179`) and MCP
(`McpPlaylistTools.kt:261`); Jellyfin capabilities list declares it
(`JellyfinProtocolCapabilities.kt:86`) but the controller exposes no rename route, and
the PWA has no UI. Problem: a typo'd name is permanent; delete-and-recreate loses order.
Recommendation: add the Jellyfin rename endpoint (capability already declared), a core
service method, and inline title edit on the detail page. Effort: Medium. Confidence:
high (skeptic-confirmed incl. backend split). Verification: rename round-trips from the
PWA and is visible over OpenSubsonic.

**4. 🟡 DO — Search errors must not masquerade as "no results"**
Location: `SearchPage.tsx:88, 129-144, 185-189, 220-256`
Evidence: `SearchAlbums`/`SearchArtists` destructure only `{data, isLoading}` and
`return null` on empty → failed sections silently vanish; failed text search shows the
error banner *and* "No matching tracks found" beneath it, with retry only for semantic
mode. Problem: user concludes the library lacks the content. Recommendation: read
`isError` in all three queries; render `LoadErrorState` with retry; make error and empty
mutually exclusive. Effort: Easy. Confidence: high (skeptic PARTIAL — banner exists for
text mode; sections and retry gap confirmed). Verification: fail the API → sections show
error+retry, no "no results" copy.

**5. 🟡 DO — Visible slider thumbs (SeekBar + VolumeControls, one CSS block)**
Location: `SeekBar.tsx:132`, `VolumeControls.tsx:49`, `index.css`
Evidence: both use `appearance-none`; zero `::-webkit-slider-thumb`/`::-moz-range-thumb`
rules exist anywhere; `accent-accent` is inert with appearance none, so Chromium/WebKit
(all iOS) render no thumb; volume additionally has no fill gradient — flat gray bar.
Problem: no visible play-head knob to grab; current volume level invisible.
Recommendation: shared thumb + fill styles in index.css. Effort: Easy. Confidence: high
(skeptic-confirmed; Firefox default thumb survives). Verification: thumb visible in
Chromium/WebKit; volume fill reflects level.

**6. 🟡 DO — MobileFullPlayer as a real dialog**
Location: `MobileFullPlayer.tsx:276-277`
Evidence: raw `fixed inset-0 z-[140]` div — no `role="dialog"`, no `aria-modal`, no
focus trap, no Escape (the recent Escape fix covers `Modal.tsx` only). Problem:
keyboard/SR users (tablets, hardware-keyboard phones) tab into the page underneath.
Recommendation: reuse `useFocusTrap` + Modal semantics + Escape. Effort: Medium.
Confidence: high (skeptic-confirmed; mobile-only surface tempers exposure).
Verification: focus cycles inside; Escape closes; SR announces dialog.

**7. 🟡 DO — Popover focus management pattern (SortMenu + GlobalSearchBar + TrackRowMenu)**
Location: `SortMenu.tsx:135-197`, `GlobalSearchBar.tsx:77-124`,
`TrackRowMenu.tsx:61-87, 172-177`
Evidence: all three drop focus to `<body>` on close; TrackRowMenu claims `role="menu"`
with no arrow-key nav and no focus move-in; search overlay has backdrop but no trap, no
dialog role, no scroll lock; SortMenu lacks Home/End and stays open on Tab-out.
Problem: keyboard users stranded after every popover interaction. Recommendation: one
shared focus-return/roving-focus hook applied to all three. Effort: Medium. Confidence:
high. Verification: close any popover → focus back on trigger; arrows navigate menu.

**8. 🟡 DO — Actionable toasts must not race the dismiss timer**
Location: `Toast.tsx:38-48, 97-133`, caller `PlayerBar.tsx:291`
Evidence: unconditional `setTimeout` regardless of `action`; no pause on hover/focus;
the only action toast ("Playback failed — Retry") lives 8s; container unmounts when
empty (unreliable live-region); all toasts assertive. Problem: keyboard/SR users may
never reach Retry (WCAG 2.2.1). Recommendation: persist (or pause-on-hover/focus)
actionable toasts; keep container mounted as persistent live region; `polite` for
non-errors. Effort: Medium. Confidence: high (skeptic PARTIAL — 8s not 5s; mechanics
confirmed). Verification: Retry toast stays until acted on or dismissed.

**9. 🟡 DO — Form error a11y: role="alert" + aria-invalid/describedby**
Location: `LoginPage.tsx:74-118`, `SettingsPage.tsx:290-337`, `UsersPanel.tsx:63-122`
Evidence: error banners are plain divs (no `role="alert"`); field errors not linked via
`aria-describedby`, no `aria-invalid`, no focus-to-first-invalid; Add-User server error
renders below the buttons, away from the field. Problem: SR users submit into silence.
Recommendation: alert roles on the three banners, field association, focus management,
error adjacent to the Username input. Effort: Easy. Confidence: high. Verification: SR
announces errors on submit.

**10. 🟡 DO — Enter submits: Add-User dialog + Group Listen inputs**
Location: `UsersPanel.tsx:83-138`, `GroupSyncPanel.tsx:226-243, 264-283`
Evidence: inputs in bare divs with onClick buttons; every other form in the app submits
on Enter. Problem: muscle memory breaks; keyboard users must mouse to the button.
Recommendation: wrap in `<form onSubmit>` with `type="submit"`. Effort: Easy.
Confidence: high. Verification: Enter creates user / joins group.

**11. 🟡 DO — Per-route document.title**
Location: `RouteTracker.tsx:9-11`, `index.html:18`
Evidence: grep for `document.title|useDocumentTitle|Helmet` = zero hits; RouteTracker
sets only the error-reporter route. Problem: every tab/bookmark/history entry reads
"Yay-Tsa". Recommendation: set `document.title` in RouteTracker (it already knows the
route). Effort: Easy. Confidence: high (skeptic-confirmed). Verification: tab shows
"Favorites — Yay-Tsa" etc.

**12. 🟡 DO — Nav: aria-current + text labels on bottom tabs**
Location: `RootLayout.tsx:226-276` (+ stale "7 items" comment :247, actual 8)
Evidence: plain `Link` (not NavLink), no `aria-current` anywhere in the file; 8
icon-only tabs (`<item.icon />`) with aria-label but no visible text. Problem: active
state is color-only; Disc3 vs ListMusic vs BookOpen vs Users are guess-work.
Recommendation: `aria-current="page"` on sidebar+tabs; short labels under icons (or
trim to ~5 primary tabs); fix the comment. Effort: Easy. Confidence: high
(skeptic-confirmed). Verification: SR announces current page; labels visible at 375px.

**13. 🟡 DO — Detail pages: partial failure must not blank the page**
Location: `AlbumDetailPage.tsx:65-76`, `ArtistDetailPage.tsx:38-49`
Evidence: `if (albumError || tracksError)` → full-page error; `isLoading = albumLoading
|| tracksLoading` → fast header waits for slow track list. Problem: loaded context
discarded; page feels broken/slow when half of it is fine. Recommendation: render
header from the resolved query; scope error/loading to the list region. Effort: Medium.
Confidence: high. Verification: fail tracks query → header + scoped error render.

**14. 🟡 DO — Playlist edit feedback: reorder rollback toast + remove undo**
Location: `PlaylistDetailPage.tsx:150-152, 274-283`, `usePlaylists.ts:145-149`
Evidence: optimistic reorder rolls back on error with no toast; remove-track is one
click, success toast confirms the loss with no way back. Problem: silent snap-back reads
as "app is broken"; misclick loses curated position. Recommendation: error toast on
rollback; Undo action on the removal toast (rides on item 8). Effort: Medium.
Confidence: high. Verification: failed reorder toasts; Undo restores track at index.

**15. 🟡 DO — Audiobook Mark-finished/Restart: pending state, feedback, confirm**
Location: `AudiobooksPage.tsx:200-210`, `useAudiobooks.ts:228-248`
Evidence: buttons ignore `isPending`; `Promise.allSettled` swallows failures; mark-
finished calls `clearLocalResume` — discards position deep in a 20-hour book with no
confirm. Problem: double-clicks, silent failures, unrecoverable position loss.
Recommendation: pending/disabled state, failure toast, confirm gate. Effort: Easy.
Confidence: high. Verification: buttons disable while pending; confirm before discard.

**16. 🟡 VERIFY(gate: 1000+ album library, scroll /albums deep with DevTools
Performance + heap profile; virtualize only if jank/heap confirms) — Grid
virtualization for albums/artists**
Location: `AlbumGrid.tsx:21`, `ArtistsPage.tsx:54`, `useInfiniteLibraryQuery.ts:74`
Evidence: raw `.map()` grids; MAX_RETAINED_PAGES=50 → up to 2500 mounted cards; track
lists ARE virtualized. Effort: Hard.

**17. 🟡 DO — Offline Library init-flash**
Location: `OfflineLibraryPage.tsx:13-14, 44-50`, `offline.store.ts:49`
Evidence: store has `initialized` flag; page never reads it → "No downloads yet."
renders during IDB init — exactly on the offline cold start the page exists for.
Recommendation: gate empty state on `initialized`. Effort: Easy. Confidence: high
(skeptic-confirmed). Verification: cold offline load shows spinner, then list.

**18. 🟡 DO — Terminology sweep: favorites/liked + downloads naming**
Location: `OfflineManager.tsx:91-121`, `PlayerBar.tsx:224`, `OfflineIndicator.tsx:17`,
`SettingsPage.tsx:212-218`, `OfflineLibraryPage.tsx:37`
Evidence: heart = "favorites" app-wide, but settings say "Auto-download liked songs" /
"Remove downloads when unliked" (same signal); DJ thumbs-up toasts "Liked" (different
signal); downloads page named four ways (Downloads / downloaded library / Offline
Downloads / route /offline). Recommendation: one word per concept (Favorites;
Downloads); reword thumbs-up toast (e.g. "Thanks — more like this"). Effort: Easy.
Confidence: high. Verification: grep shows consistent copy.

**19. 🔵 DO — Media Session ignores audiobook playbackRate**
`player.store.ts:471-479` never passes rate to `updatePositionState` → lock-screen
scrubber drifts at 1.5×/2×. Pass `get().playbackRate`. Effort: Easy.

**20. 🔵 DO — Terminal playback-failure toast**
`player.store.ts:233-237` — after 3 consecutive load failures playback stops with only
a log. Emit an error toast with Retry. Effort: Easy.

**21. 🔵 DO — Focus-visible restoration for inputs**
`index.css:242-246` strips `:focus-visible` outline from all inputs/selects/textareas;
`GlobalSearchBar.tsx:110` has no replacement. Replace blanket `outline:none` with a
token-based focus ring. Effort: Easy.

**22. 🔵 DO — A11y small-batch**
`LoadingSpinner.tsx:7-12` (`role="status"` + label), skip-to-content link +
`<main tabIndex={-1}>` (`RootLayout.tsx:170-184`), Modal container `tabIndex={-1}`
fallback (`Modal.tsx:56-70`), splash pulse reduced-motion guard inline in
`index.html:37-43`. Effort: Easy.

**23. 🔵 DO — Empty/error-state guidance sweep**
`AlbumsPage.tsx:45-49` / `ArtistsPage.tsx:39-43` (bare "No albums/artists found" — reuse
EmptyLibraryGuidance-style copy), `ExploreNew.tsx` (vanishes silently; DailyMix has an
empty message), `HomePage.tsx:7` (count error swallowed), `AudiobooksPage.tsx:294-307`
(error banner + empty copy render together — make exclusive). Effort: Easy.

**24. 🔵 DO — Zero-track album: disable Play, explain**
`AlbumDetailPage.tsx:135-167` — "0 tracks" with live Play that does nothing. Effort: Easy.

**25. 🔵 DO — Truncated-title tooltips + alt fallbacks + cover-fallback glyph**
`AlbumCard.tsx:75-81`, `ArtistCard.tsx:16-21`, `TrackList.tsx:221-224` (add `title`),
`MediaCard.tsx:55-67` (`alt` can be undefined), `image-placeholder.ts` (flat #333 reads
as load failure — add music glyph/monogram). Effort: Easy.

**26. 🔵 DO — Mobile mini-bar hairline progress**
`PlayerBar.tsx:452-499` — no progress indication on mobile's most-seen surface; 2px
hairline from the timing micro-store. Effort: Easy.

**27. 🔵 DO — Offline banner overlap + track-row overlay touch fallback**
`OfflineIndicator.tsx:14` (fixed top-0 overlaps page header — compensate padding when
offline), `TrackList.tsx:104` (art play overlay hover-only; siblings use
`max-md:opacity-60`). Effort: Easy.

**28. 🔵 DO — 404 page: "Go home" link**
`NotFound.tsx:5-10` — bare h1 dead-end. Effort: Easy.

**29. 🔵 DO — Downloads settings polish**
`OfflineManager.tsx:93-118` (show used/limit, clamp input, hint about auto-download),
`TrackRowMenu.tsx:203, 214` (disabled items: add "Needs a connection" title). Effort: Easy.

**30. 🔵 DO — Micro-polish batch**
PasswordReveal near acting row + clipboard-failure handling (`UsersPanel.tsx:12-26,
286-311`), duplicate-playlist-name soft warning (`CreatePlaylistModal.tsx:32`),
same-password check (`SettingsPage.tsx:108-115`), Remember-me helper text
(`LoginPage.tsx:129-131`), Favorites-tab persistence (`FavoritesPage.tsx:32`), karaoke
aria-pressed align (`MobileFullPlayer.tsx:136` vs `PlayerBar.tsx:441`), karaoke preset
touch targets (`KaraokeBlendSlider.tsx:51-62`). Effort: Easy.

**31. 🔵 DO — Add-to-playlist picker filter input**
`AddToPlaylistModal.tsx:69-86` — 264px scroll box painful past ~15 playlists. Effort: Easy.

**32. 🔵 VERIFY(gate: 500-track radio queue on throttled mobile profile, frame times) —
Queue virtualization** — `QueuePanel.tsx:86-152`. Effort: Medium.

**33. 🔵 VERIFY(gate: deep-scroll /albums → detail → Back; assert restored offset,
incl. cache-cold reload) — Scroll-restoration single-rAF clamp** —
`RootLayout.tsx:99-102`. Effort: Medium.

**34. DON'T(spinners adequate; instead delete unused `skeleton-bg`/`animate-skeleton`
utilities `index.css:62,325` as dead code) — Skeletons repo-wide.**

**35. DON'T(hardware Back + chevron already dismiss; swipe conflicts with vertical
scroll/seek surfaces) — Swipe-to-dismiss full player.**

**36. DON'T(hardware volume keys are the mobile convention) — Volume/mute on touch.**

**37. DON'T(refuted: `cache-eviction.ts:20-22` — only `listening-cache` entries evict;
manual/favorite/album/playlist downloads never touched) — "Silent LRU eviction of
downloads".**

### What I didn't touch

Deterministic a11y mechanics (axe/contrast — covered by /qa refs), the live-app
walkthrough (that's /qa's Walkthrough Delta Pass), backend protocol adapters beyond the
rename-capability check, and packages/core internals with no UI surface.

### Done-well baseline (so future runs don't churn)

Modal primitive (trap/Escape/aria/restore), SeekBar keyboard + aria-valuetext,
stream-error toasts with working Retry + media-error recovery, Media Session (artwork,
dynamic prev/next, audiobook skips), keyboard drag-reorder, bidirectional infinite
scroll with failed-page retry, EmptyLibraryGuidance, deep-link auth preservation incl.
query+hash, hardware-Back closes full player, pb-mobile-nav padding, PWA prompt-driven
update flow, safe-area handling, centralized tokens + 44px touch utilities,
prefers-reduced-motion global guard, sort persistence, offline cover-blob fallback.
