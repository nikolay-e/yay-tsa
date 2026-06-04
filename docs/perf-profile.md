# Load-performance profile (pages / PWA)

## Methodology & honesty about what's measured

Three independent layers were profiled. Two were **measured here** with real numbers; the browser
layer **cannot be measured in this sandbox** (the network policy blocks the Playwright/Lighthouse
Chromium download), so a reproducible harness is provided to capture it on a browser-capable machine.

| Layer                                  | Tool (reproducible)                                                                                                                    | Status here                        |
| -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- |
| Bundle / chunks                        | `npm run profile:bundle`                                                                                                               | **measured**                       |
| Backend API + DB                       | `./gradlew :app:test --tests '*ItemsEndpointBenchmark'`; `bash yay-tsa-v2/perf/run.sh`; `yaytsa.profiling.enabled=true` request filter | **measured**                       |
| Browser (FCP/LCP/TTFB/marks/waterfall) | `npm run profile:web` (Playwright) + `?perf` boot marks                                                                                | **harness ready, needs a browser** |

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
                                          ~38 images @300×300 (lazy, in-viewport load now)
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

| Page           | Initial calls (parallel unless noted)                              | API/DB (measured)                 | Render gate        | Images                |
| -------------- | ------------------------------------------------------------------ | --------------------------------- | ------------------ | --------------------- |
| **Home (/)**   | RadioSeeds + DailyMix(30) — `staleTime:0`                          | **not benchmarked (adaptive/ML)** | none (after auth)  | ~38 @300px            |
| **Albums**     | useInfiniteAlbums (50)                                             | 8 SQL / ~22 ms                    | none               | ~12–20 visible @300px |
| **Songs**      | useInfiniteTracks (50); semantic = 2-req waterfall on type         | 10 SQL / ~24 ms                   | none               | per-row @48px         |
| **Favorites**  | useInfiniteTracks isFavorite (50)                                  | 13 SQL / ~22 ms                   | none               | per-row @48px         |
| **Artists**    | useInfiniteArtists (50)                                            | ~ artists query                   | none               | ~12–20 @300px         |
| **Search**     | text via /Items; semantic = searchByText→getItemsByIds (waterfall) | 14 SQL / ~26 ms                   | none               | mixed                 |
| **PWA reopen** | same as route + SW navigation NetworkFirst(10s)                    | n/a                               | spinner until auth | from SW cache         |

Browser columns (TTFB/FCP/LCP/INP, request count, transferred bytes, image bytes) are produced by
`npm run profile:web` (writes `profile-artifacts/*.json` + a min/median/max table) — run on a browser
machine; the `?perf` marks (`app_start`, `auth_restore`, `first_route_render`) are emitted by
`src/shared/perf/perf.ts` and read by that harness and by `window.__perfDump()`.

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

**P2 — List-page images request 300×300 (~1–2 MB on Home).** _(evidenced.)_
`loading="lazy"` is already set. → **Fix:** request ~160 px for grid thumbnails and serve WebP; keep
300 px for detail pages. → **Verify:** `profile:web` KB + LCP per page.

**P2 — FavoritesPage lazy chunk 18.5 kB (dnd-kit).** _(measured.)_ Only loads on Favorites; optional:
lazy-load dnd-kit only in custom-order mode.

**Not a bottleneck (do not touch):** list-page `/Items` SQL/DB (measured fast, N+1 already fixed);
main-thread boot work (minimal); queue/render hot paths (memoized in an earlier task).

## 6. Optimization plan

- **Quick wins:** P0 optimistic auth gate; P1 Home `staleTime`; P1 SW navigation timeout 10 s→3 s
  (or SWR); P2 image thumbnail size. All small, localized changes.
- **Medium refactors:** code-split the `index` entry / lazy `tailwind-merge`; prefetch first list page
  on nav hover; benchmark + (if slow) cache the adaptive Home endpoints.
- **Do not touch:** list-page DB/API, boot CPU, queue/list rendering — measured to be fine.

## 7. Reproduce

```bash
npm run profile:bundle                                   # chunk sizes + critical total
npm run profile:web                                      # browser metrics (needs a browser)
cd yay-tsa-v2 && PERF_DB_URL=... ./gradlew :app:test --tests '*ItemsEndpointBenchmark'   # API+SQL/req
bash yay-tsa-v2/perf/run.sh                               # DB EXPLAIN/plans at scale
# backend per-request: start the app with -Dyaytsa.profiling.enabled=true and watch the
# "RequestProfiling cid=… {METHOD} {URI} status=… {ms} sql=… bytes=…" log lines
# browser marks: open any page with ?perf and call window.__perfDump()
```

## Answers (the report's questions)

- **What's slow / where / when:** cold-start time-to-meaningful-UI on **every page**, driven by the
  **auth render-gate** (P0) on top of an over-budget **critical JS bundle** (P1); **Home** additionally
  re-runs uncached adaptive queries (P1); **warm PWA reopen** can stall on the SW 10 s navigation
  timeout (P1). List-page API/DB is **not** slow (measured).
- **How much time:** quantify with the harness — `first_route_render − app_start` (gate + parse) and
  FCP/LCP per scenario; server-side list APIs are ~20–26 ms p50 (measured) so they're not the cost.
- **Why:** UI blocked on `/Users/Me`; 179 kB eager JS; `staleTime:0` on Home; NetworkFirst(10 s).
- **Biggest-effect fix:** the **optimistic auth gate** (P0) — removes a full RTT from first paint on
  every cold start — followed by the bundle trim and Home caching.
- **How we verify faster:** re-run `profile:web` (FCP/LCP/marks min/med/max) and `profile:bundle`
  before/after; compare the `auth_restore` and `first_route_render` marks.
