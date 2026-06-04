# /Items performance report

Reproduce with `bash perf/run.sh` (PostgreSQL 16 + pg_trgm, no Docker). Numbers below are
PostgreSQL **server-side execution time**, warm cache, `fsync=off`, `max_parallel_workers=0`, on the
CI/sandbox host. They capture **plan shape and relative cost**, not absolute production latency
(network + JVM row mapping are not included — that is exactly why the N+1 and "load-all" row counts
matter beyond DB time). Plan shapes are stable run-to-run; millisecond figures vary ±20%.

## 1. Per-scenario plans & timing (page = 50 items)

| Scenario                                | 1k ent. | 5k ent. | 20k ent. | Plan (20k)                                                   |
| --------------------------------------- | ------- | ------- | -------- | ------------------------------------------------------------ |
| Albums browse, sort_name, page 1        | 0.04    | 0.04    | 0.06     | Index Scan `idx_entities_type_sort_name`                     |
| Albums browse, sort_name, deep offset   | 0.11    | 0.38    | 1.37     | **Sort** (OFFSET re-scans skipped rows)                      |
| Songs browse, sort_name, page 1         | 0.03    | 0.04    | 0.06     | Index Scan `idx_entities_type_sort_name`                     |
| Songs recently-added, created_at DESC   | 0.02    | 0.02    | **0.02** | **Index Scan Backward `idx_entities_type_created_at`** (new) |
| Songs recently-added + `id` tie-breaker | 0.07    | 0.07    | 0.07     | Incremental Sort (≈ +0.05 ms)                                |
| Favorites load-all, ORDER BY position   | 0.08    | 0.17    | 0.32     | **Index Scan `idx_favorites_user_position`** (new)           |
| Favorites SQL page, offset 0            | 0.04    | 0.03    | 0.02     | Index Scan `idx_favorites_user_position`                     |
| Search, name ILIKE                      | 0.41    | 2.0     | 0.84     | Seq Scan → **Bitmap Heap (trgm gin)** kicks in at scale      |

## 2. New indexes — ON vs OFF (@ 20k entities / 2k favorites)

| Query                                     | With index  | Without index      | Plan without                   |
| ----------------------------------------- | ----------- | ------------------ | ------------------------------ |
| Recently-added (created_at DESC LIMIT 50) | **0.09 ms** | **4.28 ms** (~47×) | Seq Scan all 17k tracks + Sort |
| Favorites load-all (2k favs)              | **0.39 ms** | 0.70 ms (~1.8×)    | Seq Scan + Sort                |

- `idx_entities_type_created_at` is the clear win: without it, "recently added" scans and sorts the
  **entire** track set just to return 50 rows. With it, it reads 50 rows and stops.
- `idx_favorites_user_position` removes the sort step from the favorites load. At 2k favorites the
  absolute gain is small (the sort of 2k rows is cheap); the benefit grows with favorite count and,
  more importantly, makes the load an ordered index scan instead of a sort-the-world operation.

## 3. The favorites "load-all" question (in-memory `.drop/.take` vs SQL pagination)

The favorites listing loads **all** of a user's favorites per page, then slices in Kotlin. Sweep of
load-all vs a deep SQL `OFFSET … LIMIT 50`:

| favorites | load-all (current) | deep SQL OFFSET |
| --------- | ------------------ | --------------- |
| 5,000     | 1.5 ms             | 1.4 ms          |
| 20,000    | 8.2 ms             | 7.1 ms          |
| 50,000    | 20.4 ms            | 18.5 ms         |
| 100,000   | 63 ms              | 48 ms           |

**Key result: a deep SQL `OFFSET` is essentially the same cost as load-all — both are O(N)**, because
OFFSET still walks every skipped row. So converting favorites to SQL `OFFSET/LIMIT` pagination is
**not** a meaningful DB win. It only trims the network/JVM mapping (50 rows vs N). The only way to
make deep favorites pages O(page) is **keyset/cursor** pagination on the position-sorted path.

Threshold: load-all stays sub-2 ms up to ~5k favorites and only becomes notable at ≥20k (8 ms) /
≥50k (20 ms). For a personal music server, 20k+ favorited tracks is extreme.

## 4. Tie-breaker

`created_at` (and `sort_name`) are **not a total order** — the harness seeds up to 10 tracks sharing
a timestamp, and `count(*) = count(distinct created_at)` is `false`. With plain `ORDER BY created_at`,
OFFSET pages can skip/duplicate rows where values tie. Fixed in `browseTracks` by appending `id` as a
stable secondary sort. Cost: Index Scan + Incremental Sort, **~0.05 ms** extra (table §1). The
2-column index is kept; the incremental sort over tiny tie-groups is cheaper than bloating the index
with the 16-byte uuid.

## Conclusion (definition of done)

- **Where the bottleneck was.** (a) `Album.toBaseItem` issued **2 `getArtist()` calls per album**
  (each = entity+artist+image queries) → a true N+1 on the Albums tab, search and artist-albums.
  (b) Missing indexes made "recently added" scan+sort the whole track table (~47× slower) and
  favorites sort-the-world. (c) Frontend: appending a page re-rendered the entire list (no memo).
- **What got better.** Album artist names are batched into one query per page (N+1 gone, locked by a
  query-budget test). Recently-added is an index scan (0.09 vs 4.28 ms @20k). Favorites load is an
  ordered index scan. Frontend rows/cards are memoized so a page append only renders new items.
  Pagination is now stable under tied sort keys.
- **What remains.** Deep `OFFSET` is O(N) for both albums browse and favorites — acceptable at
  realistic sizes (sub-2 ms to ~5k; ~8 ms at 20k). Album/artist **grids are not virtualized**, so a
  very long browse keeps many DOM nodes. Album/artist browse sort still lacks the `id` tie-breaker
  (only `browseTracks` was fixed).
- **Cheapest next fix, max effect (only if metrics demand it).** Keyset pagination for the default
  favorites position sort (O(page) instead of O(N)) — the single change that removes the remaining
  O(N) behaviour. SQL `OFFSET` pagination is explicitly **not** worth doing (proven above). Grid
  virtualization and the artist/album tie-breaker are smaller, independent follow-ups.
