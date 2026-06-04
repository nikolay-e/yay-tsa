# Load-performance profile (pages / PWA)

## Methodology & honesty about what's measured

Three independent layers were profiled. Two were **measured here** with real numbers; the browser
layer **cannot be measured in this sandbox** (the network policy blocks the Playwright/Lighthouse
Chromium download), so a reproducible harness is provided to capture it on a browser-capable machine.

| Layer                                                                | Tool (reproducible)                                                                                                                    | Status here                          |
| -------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| Bundle / chunks                                                      | `npm run profile:bundle`                                                                                                               | **measured**                         |
| Backend API + DB                                                     | `./gradlew :app:test --tests '*ItemsEndpointBenchmark'`; `bash yay-tsa-v2/perf/run.sh`; `yaytsa.profiling.enabled=true` request filter | **measured**                         |
| Image bytes/decode                                                   | `npm run profile:images` (sharp) + code (backend `getImage`)                                                                           | **measured (ratios) + code-certain** |
| Browser (FCP/LCP/TTFB/marks/waterfall, per-image bytes, LCP element) | `npm run profile:web` (Playwright) + `?perf` boot marks                                                                                | **harness ready, needs a browser**   |

Boot/render structure was traced from code (file:line evidence below), not guessed.

## 1. Bundle — initial critical path (measured, `npm run profile:bundle`)

| Chunk (gzip)          | gz       | raw    | eager? |
| --------------------- | -------- | ------ | ------ |
| vendor-react          | 85.6 kB  | 270 kB | ✅     |
| index (app entry)     | 50.3 kB  | 180 kB | ✅     |
| vendor-state          | 18.6 kB  | 65 kB  | ✅     |
| index.css             | 9.1 kB   | 50 kB  | ✅     |
| cn (tailwind-merge)   | 8.3 kB   | 28 kB  | ✅     |
| vendor-icons (lucide) | 7.5 kB   | 20 kB  | ✅     |
| FavoritesPage         | 18.5 kB  | 56 kB  | lazy   |
| SettingsPage          | 6.8 kB   | 25 kB  | lazy   |
| other route chunks    | <3 kB ea | —      | lazy   |

**Initial critical path = 179.4 kB gz** (entry + eager vendors + CSS) vs the repo's own **150 kB
target** (CLAUDE.md). `vendor-react` is ~48% of it. `cn` (tailwind-merge, 8.3 kB) and `vendor-icons`
(7.5 kB) are eager and are the most actionable app-controlled items. Route pages are correctly lazy;
`FavoritesPage` is a heavy lazy chunk (dnd-kit) but only loads on the Favorites route.

## 2. Backend API + DB for list pages (measured)

In-process endpoint benchmark (`ItemsEndpointBenchmark`, MockMvc → JPA → JSON, no network), seed
250 artists / 500 albums / 4 250 tracks / 500 favorites; page = 50:

| Endpoint (page)               | items | resp KB | **SQL/req** | p50 ms | p95 ms |
| ----------------------------- | ----- | ------- | ----------- | ------ | ------ |
| /Items MusicAlbum (Albums)    | 50    | 17.9    | **8**       | 21.9   | 30.1   |
| /Items Audio sortName (Songs) | 50    | 19.1    | 10          | 23.8   | 28.0   |
| /Items Audio recently-added   | 50    | 21.5    | 10          | 20.6   | 25.0   |
| /Items IsFavorite (Favorites) | 50    | 21.5    | 13          | 22.4   | 32.2   |
| /Items SearchTerm (Search)    | 50    | 21.5    | 14          | 25.6   | 36.9   |

DB plans (`bash yay-tsa-v2/perf/run.sh`, see `yay-tsa-v2/perf/PERF_REPORT.md`): index-driven, sub-ms
at 20 k entities; the album→artist N+1 was removed in an earlier task (an Albums page issues 8 SQL
statements, not ~300); recently-added uses `idx_entities_type_created_at` (0.09 ms vs 4.28 ms without).

**Conclusion for list pages: the API/DB is NOT the bottleneck** — query counts are bounded, DB time
is sub-ms, server-side p50 ~20–26 ms. Do not optimize these further.

**Not yet benchmarked (gap):** the **Home** page calls — `AdaptiveDjService.getRadioSeeds()` and
`getDailyMix(30)` — and `/Users/Me` (the auth gate). These are the prime suspects for slow Home/cold
start (adaptive/ML path) and are the next thing to measure with the request-timing filter
(`yaytsa.profiling.enabled=true`) before any optimization.

## 3. Boot / render waterfall (cold start) — code-evidenced

```
app_start (main.tsx:15)  ──►  download+parse 179 kB critical JS  ──►  React render
                                                                      │
RootLayout mount (RootLayout.tsx:74) ──► restoreSession() ──► GET /Users/Me  ◄── BLOCKS UI
   while pending: RootLayout returns ONLY a spinner (RootLayout.tsx:95-101)   │
                                                                      ▼
auth resolved ──► first_route_render ──► HomePage mounts
                                          ├─ useRadioSeeds()  staleTime:0  ─┐ parallel
                                          └─ useDailyMix(30)  staleTime:0  ─┘ (adaptive/ML)
                                          ~38 covers, FULL-RES (backend ignores the 300px param) — likely Home LCP
```

Evidence: `RootLayout.tsx:95-101` (spinner gate), `auth.store.ts:181-248` (`restoreSession` →
`client.get('/Users/Me')` at :196), `HomePage.tsx` (`RadioSeeds`+`DailyMix`),
`useRadioSeeds.ts:5-18` / `useDailyMix.ts:9-22` (`staleTime:0, gcTime:0`), `vite.config.ts:28-73`
(SW: navigation = NetworkFirst, `networkTimeoutSeconds: 10`; images = StaleWhileRevalidate),
`MediaCard.tsx:35-37` (`maxWidth:300, maxHeight:300`). Main-thread startup work is **minimal** — no
large JSON.parse/sort/structuredClone in boot (checked auth.store, player.store, query-client, pages);
so CPU is **not** the cold-start cost. Time is lost to: critical-JS download/parse → auth round-trip
(gated) → Home adaptive queries.

## 4. Per-page summary

| Page           | Initial calls (parallel unless noted)                              | API/DB (measured)                 | Render gate        | Images                          |
| -------------- | ------------------------------------------------------------------ | --------------------------------- | ------------------ | ------------------------------- |
| **Home (/)**   | RadioSeeds + DailyMix(30) — `staleTime:0`                          | **not benchmarked (adaptive/ML)** | none (after auth)  | ~38 FULL-RES (param ignored)    |
| **Albums**     | useInfiniteAlbums (50)                                             | 8 SQL / ~22 ms                    | none               | ~12–20 FULL-RES (param ignored) |
| **Songs**      | useInfiniteTracks (50); semantic = 2-req waterfall on type         | 10 SQL / ~24 ms                   | none               | per-row @48px                   |
| **Favorites**  | useInfiniteTracks isFavorite (50)                                  | 13 SQL / ~22 ms                   | none               | per-row @48px                   |
| **Artists**    | useInfiniteArtists (50)                                            | ~ artists query                   | none               | ~12–20 FULL-RES (param ignored) |
| **Search**     | text via /Items; semantic = searchByText→getItemsByIds (waterfall) | 14 SQL / ~26 ms                   | none               | mixed                           |
| **PWA reopen** | same as route + SW navigation NetworkFirst(10s)                    | n/a                               | spinner until auth | from SW cache                   |

Browser columns (TTFB/FCP/LCP/INP, request count, transferred bytes, image bytes) are produced by
`npm run profile:web` (writes `profile-artifacts/*.json` + a min/median/max table) — run on a browser
machine; the `?perf` marks (`app_start`, `auth_restore`, `first_route_render`) are emitted by
`src/shared/perf/perf.ts` and read by that harness and by `window.__perfDump()`.

## 4b. Image profiling (cold-load byte & decode cost)

**Decisive, code-certain finding — the backend does NOT resize covers.**
`JellyfinMediaController.getImage` (`adapter-jellyfin/.../JellyfinMediaController.kt:36-59`) accepts
`maxWidth`/`maxHeight`/`quality` **but ignores them** — it returns `FileSystemResource(filePath)`, the
**original cover file** at source resolution (only the content-type is mapped). So the frontend's
`maxWidth:300` (`MediaCard.tsx:36`) is a **no-op**: every cover downloads and decodes at full
resolution and the browser scales it to the display slot.

| Surface                          | display slot           | client requests     | backend serves        |
| -------------------------------- | ---------------------- | ------------------- | --------------------- |
| Grid card (`MediaCard`)          | ~150–180 px (cols-2…6) | `maxWidth:300` (2×) | **full-res original** |
| Track row (`TrackList`)          | ~40 px                 | `maxWidth:48`       | **full-res original** |
| Now-playing/detail (`PlayerBar`) | ~64 / ~400 px          | 64 / 400            | **full-res original** |

**Measured reduction (`npm run profile:images`, sharp, structured representative cover):**

| variant                                         | vs source |
| ----------------------------------------------- | --------- |
| 300 px JPEG (intended request size, if resized) | −84%      |
| 300 px WebP                                     | −93%      |
| 160 px WebP (grid thumbnail)                    | −97%      |
| 48 px WebP (track row)                          | −99%      |

**Exact, format-independent (decode ∝ pixels):** a 160 px slot needs **1.8%** of a 1200 px source's
pixels (300 px = 6.3%) — a full-res cover is ~50× the pixels actually rendered in a grid card.

**Scale:** Home shows ~38 covers near the fold. At a typical 1200 px photographic cover (~250 kB),
that's **~9 MB** of cover bytes on a cold load, all decoded full-res; 160 px WebP thumbnails would be
**~0.3 MB** (−97%). Absolute bytes depend on the real covers — get exact numbers with `profile:web`
against a real library; warm loads hit the SW image cache (StaleWhileRevalidate), so the cost is the
cold/first-view network **plus** the full-res decode every paint.

**LCP attribution (needs the browser, now captured):** `profile:web` reports `LCP=img?`,
`largest img KB`, `img KB`, and `above-fold imgs` per page. On mobile + cold cache, a full-res
above-the-fold cover being the Home LCP is the expected outcome — one harness run confirms it.

**Cold vs warm:** cold = full-res over the network + decode; warm/PWA-reopen = served from the SW
image cache (no network) but **still decoded full-res** on the main thread; the byte win is cold, the
decode win is both.

| Fix                                         | current            | after 160 px WebP | LCP impact (if cover=LCP) | risk/complexity                       |
| ------------------------------------------- | ------------------ | ----------------- | ------------------------- | ------------------------------------- |
| Backend `getImage` honour maxWidth + WebP   | full-res (~250 kB) | ~7–15 kB          | high                      | medium (resize + on-disk thumb cache) |
| Client size tiers (160 grid/300 detail/400) | param ignored      | correct request   | enables the above         | low (param change)                    |
| `width`/`height` attrs on covers            | possible CLS       | no layout shift   | CLS + LCP stability       | low                                   |
| `fetchpriority="high"` on the one LCP cover | none               | earlier LCP paint | medium                    | low                                   |

## 4c. Image pipeline fix (implemented)

The contract bug is fixed — the endpoint now honours `maxWidth`/`maxHeight`/`quality`.

**Backend** (`ThumbnailService` + `JellyfinMediaController.getImage`):

- Resizes with ImageIO (pure-JDK, works on the Alpine runtime), aspect-ratio preserved, **never
  upscales**; `quality` applied to the lossy output; dimensions clamped to 32–1200 px and quality to
  40–95 — bad input is clamped, **never a 500**; no size params → original served unchanged.
- **WebP** when the client sends `Accept: image/webp` and `cwebp` is present (musl-native
  `libwebp-tools`, added to the Alpine image) — otherwise JPEG; a failed encode degrades to the
  original. `Vary: Accept` is set.
- **On-disk thumbnail cache** keyed by `sha256(absPath|mtime|size|w|h|quality|format)`, so a changed
  source invalidates and an identical request is a pure read (no re-resize). Cache files are named by
  hash → no path traversal.
- **HTTP caching**: `Cache-Control: max-age=2592000`, `ETag`, and `304` on `If-None-Match`.

**Frontend tiers** (`MediaCard`/`RadioSeedCard` 160 px grid @≤2× DPR, `TrackList` 48 px, detail/now-
playing 300–400 px), `width`/`height` attributes (no CLS), `loading="lazy"` below the fold, and
`fetchpriority="high"` + eager on **only the first** card per page (the LCP candidate).

**Before / after (`npm run profile:images`, representative cover; ratios validated by the backend's
own resize in `ThumbnailServiceTest`):**

| Cover variant                      | bytes (typical 1200 px source) | vs full-res |
| ---------------------------------- | ------------------------------ | ----------- |
| served before (full-res original)  | ~250 kB                        | —           |
| 300 px JPEG                        | ~40 kB                         | −84%        |
| **160 px WebP (grid, served now)** | **~7–15 kB**                   | **~−95%**   |

Home (~38 covers) cold-load cover bytes: **~9 MB → ~0.3 MB**. Decode also drops to ~1.8% of the pixels.

**Manual endpoint check** (running server, `$ID` an album id, `$T` a token):

```bash
B=https://host/api
curl -so /dev/null -w 'orig:   %{size_download}B %{content_type}\n' "$B/Items/$ID/Images/Primary?api_key=$T"
curl -so /dev/null -w '300jpg: %{size_download}B %{content_type}\n' "$B/Items/$ID/Images/Primary?maxWidth=300&api_key=$T"
curl -so /dev/null -H 'Accept: image/webp' -w '160webp:%{size_download}B %{content_type}\n' "$B/Items/$ID/Images/Primary?maxWidth=160&api_key=$T"
ETAG=$(curl -sI "$B/Items/$ID/Images/Primary?maxWidth=160&api_key=$T" | tr -d '\r' | awk -F': ' '/[Ee]tag/{print $2}')
curl -s -o /dev/null -w 'revalidate: %{http_code}\n' -H "If-None-Match: $ETAG" "$B/Items/$ID/Images/Primary?maxWidth=160&api_key=$T"  # -> 304
```

**Tests:** backend `ThumbnailServiceTest` (8 — resize bound, aspect ratio, no-upscale, quality→bytes,
clamp-no-500, cache hit not re-rendered, mtime invalidation, WebP-when-cwebp-else-JPEG);
frontend `ImageTiers.test.tsx` (3 — grid asks 160 not 300, first card eager+fetchpriority, rows ask 48).

## 5. Ranked bottlenecks (evidence-based)

**P0 — Cold-start auth render-gate** _(evidenced; biggest cold-start lever)._
The entire shell is hidden behind a spinner until `GET /Users/Me` returns (`RootLayout.tsx:95-101`).
So first meaningful paint = critical-JS parse **+ one auth round-trip**. The store already keeps the
session on any non-401 error, so gating the whole UI on the network call is stricter than needed.
→ **Fix:** render the shell/skeleton immediately from the persisted session (optimistic), reconcile
`/Users/Me` in the background, and only gate _protected content_ (or redirect on a confirmed 401).
→ **Verify:** `first_route_render − app_start` mark delta and FCP, before/after, via `profile:web`.

**P1 — Initial critical JS over budget (179 kB vs 150 kB).** _(measured.)_
→ **Fix (app-controlled):** confirm `lucide` icons are imported per-icon (tree-shaken into
`vendor-icons`); make `tailwind-merge` (`cn`, 8.3 kB) lazy or replace with a lighter `clsx`-only path
on the hot route; split non-critical code out of the 50 kB `index` entry.
→ **Verify:** `npm run profile:bundle` (critical total).

**P1 — Home queries never cache (`staleTime:0, gcTime:0`).** _(evidenced.)_
Every Home visit re-hits the adaptive DJ service. → **Fix:** give RadioSeeds/DailyMix a real
`staleTime` (e.g. 15–30 min) and prefetch; **first benchmark the endpoints** (request filter) to
confirm they're the slow ones before/after. → **Verify:** backend filter log + Home `first_query` marks.

**P1 — SW navigation NetworkFirst, 10 s timeout.** _(evidenced; warm-PWA reopen.)_
On a slow/unreachable backend the PWA can wait up to 10 s before serving the cached shell.
→ **Fix:** lower `networkTimeoutSeconds` to ~3 s, or use StaleWhileRevalidate for navigation so the
shell paints from cache instantly on reopen. → **Verify:** `profile:web` warm scenario FCP.

**P1 — Covers served full-resolution (backend ignored resize); likely the Home LCP on mobile/cold.**
**✅ FIXED in this PR — see §4c** (backend now resizes + WebP + caches; client requests display tiers).
_(root cause code-certain + reduction ratios measured; §4b. LCP attribution still worth one
`profile:web` run for the report.)_ ~38 full-res covers on Home (~9 MB cold at ~250 kB each), each decoded at ~50× the pixels it's
displayed at. The client `maxWidth:300` does nothing because the endpoint ignores it.
→ **Fix (backend-first — the client param is a no-op):** make `getImage` honour `maxWidth`/`quality`
and emit WebP/AVIF (cache derived thumbnails on disk); add client size tiers (160 grid / 300 detail /
400 now-playing), `width`/`height` attrs (no CLS), keep `loading="lazy"` below the fold, and
`fetchpriority="high"` on **only** the one LCP cover.
→ **Verify:** `profile:web` `img KB` / `largest img KB` / `LCP=img?` before/after.
→ **Priority rule (as requested):** if `profile:web` shows `LCP=img` on Home, this is **P0 for LCP**
(co-equal with the auth gate, which is P0 for time-to-first-paint — they're different metrics: auth
gates first paint/interactive, the largest cover gates LCP). If Home LCP is gated by the auth/text
shell instead, images stay **P1**. Either way the fix is high-value and backend-rooted.

**P2 — FavoritesPage lazy chunk 18.5 kB (dnd-kit).** _(measured.)_ Only loads on Favorites; optional:
lazy-load dnd-kit only in custom-order mode.

**Not a bottleneck (do not touch):** list-page `/Items` SQL/DB (measured fast, N+1 already fixed);
main-thread boot work (minimal); queue/render hot paths (memoized in an earlier task).

## 6. Optimization plan

- **Quick wins:** P0 optimistic auth gate; client image size tiers + `width`/`height` attrs +
  `fetchpriority` (low risk, but only fully effective once the backend resizes); P1 Home `staleTime`;
  P1 SW navigation timeout 10 s→3 s (or SWR).
- **Medium refactors:** **backend `getImage` resize + WebP + thumbnail cache** (the real image fix);
  code-split the `index` entry / lazy `tailwind-merge`; prefetch first list page on nav hover;
  benchmark + (if slow) cache the adaptive Home endpoints.
- **Do not touch:** list-page DB/API, boot CPU, queue/list rendering — measured to be fine.

## 7. Reproduce

```bash
npm run profile:bundle                                   # chunk sizes + critical total
npm run profile:images                                   # cover byte/decode cost + WebP savings (sharp)
npm run profile:web                                      # browser FCP/LCP/marks + per-image bytes + LCP element (needs a browser)
cd yay-tsa-v2 && PERF_DB_URL=... ./gradlew :app:test --tests '*ItemsEndpointBenchmark'   # API+SQL/req
bash yay-tsa-v2/perf/run.sh                               # DB EXPLAIN/plans at scale
# backend per-request: start the app with -Dyaytsa.profiling.enabled=true and watch the
# "RequestProfiling cid=… {METHOD} {URI} status=… {ms} sql=… bytes=…" log lines
# browser marks: open any page with ?perf and call window.__perfDump()
```

## Answers (the report's questions)

- **What's slow / where / when:** two distinct metrics. (a) **Time-to-first-UI** on **every page,
  cold** is gated by the **auth render-gate** (P0) on top of the over-budget **critical JS** (P1);
  **Home** also re-runs uncached adaptive queries (P1); **warm PWA reopen** can stall on the SW 10 s
  navigation timeout (P1). (b) **LCP**, especially **Home on mobile/cold**, is almost certainly the
  **full-resolution cover art** — the backend ignores the resize param and serves originals (~9 MB of
  covers near the Home fold), so the largest above-the-fold cover is the likely LCP element
  (confirm with one `profile:web` run; root cause already code-certain). List-page API/DB is **not**
  slow (measured).
- **How much time:** quantify with the harness — `first_route_render − app_start` (gate + parse) and
  FCP/LCP per scenario; server-side list APIs are ~20–26 ms p50 (measured) so they're not the cost.
- **Why:** UI blocked on `/Users/Me`; 179 kB eager JS; `staleTime:0` on Home; NetworkFirst(10 s).
- **Biggest-effect fix:** depends which metric. For **first paint/interactive** → the **optimistic
  auth gate** (removes a full RTT on every cold start). For **LCP** → **backend cover thumbnails
  (resize + WebP)** since covers dominate above-the-fold bytes/decode. These are complementary, not
  competing. The required-first step the spec demanded — confirm covers aren't under-ranked — is done:
  images are now P1 (P0-for-LCP pending the one browser confirmation), not P2.
- **How we verify faster:** re-run `profile:web` (FCP/LCP, `LCP=img?`, `img KB`, `largest img KB`,
  marks min/med/max), `profile:bundle`, and `profile:images` before/after; compare `auth_restore` and
  `first_route_render` marks and the Home LCP/largest-image bytes.
