# LEVERAGE — /review-leverage findings log

## 2026-06-14 14:30 · 90dd7b34 · MODERNIZE TOOLING + STRUCTURE (toolchain inventory, infra-metadata-enricher, BuildSha plumbing)

### TL;DR

Toolchain is largely modern and well-chosen (ESLint 9 flat config + Prettier, Vitest 4, ruff, ktlint-via-Spotless). The headline leverage is **dead/redundant tooling, not legacy-tool swaps**: detekt is installed + configured but surgically removed from the `check` lifecycle (never runs), and the JS/TS pre-commit stack carries 2 hand-rolled bash hooks + a duplicate Python security hook that real lint rules already subsume. The new `infra-metadata-enricher` module is structurally convention-compliant; its only real issue is HTTP-client/retry duplication shared with 3 other modules. The BuildSha plumbing is justified by the PWA rollout-gate contract — leave it; only a dead build-arg is a clean fix. Recommended swaps to Biome/oxlint/pnpm are REJECTED on parity grounds.

### Top Issues

1. ✅ **detekt is dead — applied + configured but never runs.** `yay-tsa-v2/build.gradle.kts:33` applies the plugin to every leaf subproject, `:38-41` configures it from `detekt.yml` (real 563-byte config exists), but `:62-65` surgically prunes it from the `check` lifecycle: `tasks.named("check") { setDependsOn(dependsOn.filterNot { it.toString().contains("detekt") }) }` with comment "Detekt 1.x is incompatible with Kotlin 2.1". `gradle/libs.versions.toml:13` pins `detekt = "1.23.7"`. Web-verified (June 2026): **detekt 1.23.8 is compatible with Kotlin 2.1/2.2**, and the codebase is Kotlin 2.1.20 — so the disabling rationale is now false. Result: dead plugin + orphan config, zero enforcement (CI runs `./gradlew build` which never invokes it). 🟡 (silent loss of a quality gate, not a security/reliability risk — re-rated down from scout's 🔴). **Fix:** bump to 1.23.8 in `libs.versions.toml:13`, delete the prune block (`build.gradle.kts:62-65`), generate `detekt --create-baseline` so the gate goes green immediately, then triage incrementally. Verify `detekt.yml` parses under 1.23.8 (schema shifts across minors). Do NOT jump to detekt 2.0 (alpha, Kotlin 2.4). Effort: 10min config + 1-3hr triage. Highest-leverage finding.

2. ✅ **Hand-rolled `no-console` bash hook redundant with ESLint.** `.pre-commit-config.yaml:239-246` greps for `console.log|debug` and exits 1. ESLint already has the rule but at `'warn'` (`eslint.config.js:42,124,272` — `'no-console': ['warn', { allow: [...] }]`). Setting it to `'error'` is a strict superset of the bash hook (also covers core/platform, not just `apps/web/src/{features,routes,components}`). 🟡 **Fix:** flip ESLint `no-console` to `'error'` (keep `allow` list to preserve `info`), delete the bash hook. Easy, always-yes. (Confirm no intentional `console.log` exists in core/platform first.)

3. ✅ **Standalone `bandit` hook duplicates ruff.** `.pre-commit-config.yaml:144-151` runs bandit; `services/audio-ml/pyproject.toml:18` already selects ruff `"S"` (flake8-bandit) with the same skips duplicated in both ruff-ignores and a `[tool.bandit]` block. 🟡 **Fix:** drop the bandit hook + `[tool.bandit]` block, after confirming ruff `S` covers bandit `B615` (HF `from_pretrained`). Easy.

### Other findings

- 🟡 **HTTP-client + retry duplication (infra-metadata-enricher F1/F2/F3 merged).** Four outbound JDK clients duplicate `HttpClient.newBuilder` + User-Agent + timeout + `runCatching{send}` + status-`when` boilerplate: `MusicBrainzClient.kt:71-76`, `CoverArtArchiveClient.kt:22-28`, `infra-karaoke-worker/SeparatorClient.kt:24-29`, `adapter-jellyfin/LrclibClient.kt:21-25`. No `infra-http` module exists. `MusicBrainzClient.backoff()` (`:176-185`) is the only Kotlin backoff-with-jitter (a new wheel). **Latent reliability gap:** `CoverArtArchiveClient.fetch()` (`:48-53`) throws on first failure with NO retry loop, while MusicBrainz retries-then-throws — inconsistent against a flaky external service. **Fix:** (a) always-yes first step — unify CoverArt to retry via the same backoff; (b) extract a parameterized `infra-http` helper (keep deliberate per-caller variations: HTTP_1_1, redirect policy) when touching the 2nd caller. Effort: F3 small / F1 medium.
- 🟡 **Dead `GIT_SHA` build-arg for the backend image.** `.github/actions/docker-build/action.yml:121-123` passes `GIT_SHA` as a Docker build-arg, but `yay-tsa-v2/Dockerfile` has zero `ARG GIT_SHA` (verified grep -c = 0). The sha actually reaches the pod via a Helm env var (`charts/yay-tsa-v2/templates/backend-deployment.yaml:63-64`, `value = image.tag`), not at build time. Misleading no-op. **Fix:** drop the build-arg for the backend path (or add the ARG if future build-time use intended). Easy.
- 🟡 **`lodash` override `"4.18.1"` (`package.json:45`)** — off-mainline phantom version above the maintained `4.17.x` security line. Exact pin is suspicious/likely a typo. **Flag for human review** (run `npm ls lodash`); don't auto-edit.
- 🟡 **`jsdom ^29` stale major** (`package.json:68`, `apps/web/package.json:46`) while the rest of the stack is current. Easy bump (run web tests).
- 🟡 **`component-naming` grep hook** (`.pre-commit-config.yaml:248-256`) subsumable by `eslint-plugin-check-file` filename rule. Easy but adds a dep — case-by-case.
- 🔵 **knip + jscpd + type-coverage run full-repo scans per commit** (`.pre-commit-config.yaml:215-237`, all `pass_filenames: false`) plus 2 `tsc` runs. Move to `stages: [pre-push]` like `npm-audit` already is (`:206`) — faster every commit, same coverage. Easy. **Highest-ROI single move.**
- 🔵 **`apps/web` lint `--ext` dead flag** (`apps/web/package.json:10`) — ignored under ESLint 9 flat config. Trivial cleanup.
- 🔵 Kotlin 2.1.20 / Spring Boot 3.4.4 stale (Kotlin 2.4 / Spring Boot 4 GA June 2026); Spotless 7.0.2 minor behind (8.6.0 out); `build-logic` duplicates 4 version pins from `libs.versions.toml` (own TODO acknowledges). Notes — separate dependency-bump effort.

### Systemic Patterns

1. **Disable-don't-delete tooling debt** — detekt and the dead build-arg are both "wired but inert". The fix is recover-or-remove, not leave-half-on.
2. **Hand-rolled bash hooks substituting for lint rules** — `no-console` and `component-naming` reimplement in brittle `find`/`grep` what a declarative rule expresses. Real rules subsume them.
3. **Per-module HTTP boilerplate** — outbound HTTP grew organically into 4 near-identical hand-built JDK clients across 3 modules with divergent retry semantics.

### False Positives / Rejected

- **Biome / oxlint swap — REJECTED.** `eslint-plugin-boundaries` (`eslint.config.js:7,279-343`) enforces the Core→Platform→Web layering; Biome has no equivalent (hard parity blocker), its Tailwind-sort is nursery/unsafe, and oxlint type-aware is alpha (not GA, June 2026). Keep ESLint+Prettier — the "two tools" is essential, not accidental, complexity here.
- **npm → pnpm** — marginal at 4 workspace packages; case-by-case, not forced.
- **tsgo** — TS 7 native port is Beta (not GA, June 2026); optional CI-typecheck-only experiment, can't replace `tsc -b` emit.
- **semgrep removal — REJECTED (scout overstated overlap).** ESLint config imports NO security plugin; semgrep `p/javascript`/`p/typescript` carry taint/injection rules ESLint lacks. At most drop the `p/react` line; keep the rest.
- **BuildSha value-source rework — REJECTED.** The custom `ServerInfo.BuildSha` field (`JellyfinDtos.kt:88`, `JellyfinAuthController.kt:146`) is justified: the PWA rollout gate (`v2-ci.yml`) polls `/api/System/Info/Public` for PascalCase `.BuildSha == main-<sha7>`. Actuator `/actuator/info` has a different path/shape/port and won't satisfy the contract. The deployed value = image.tag (what Image Updater rolled out, correct format); Spring `buildInfo()`/`GitProperties` would report raw source-HEAD hex, wrong format, **breaking the gate**. Leave the source plumbing as-is.
- **infra-metadata-enricher `core-application:shared` dep, package name, wiring, inline Jackson DTOs, manual `@Component` client construction** — all convention-compliant with sibling workers. Non-issues.

### Total Estimated Savings

- Pre-commit hooks removable: **3** (no-console, bandit, component-naming) + **3 relocated** to pre-push (knip, jscpd, type-coverage)
- Config blocks removable: **2** (`[tool.bandit]`, detekt prune block)
- Dead flags/args removable: **2** (lint `--ext`, GIT_SHA build-arg)
- Tools to recover: **1** (detekt — whole Kotlin static-analysis gate)
- Refactors (DRY/resilience): **2** (shared infra-http helper, cover-art retry)
- Tool swaps rejected on parity: **3** (Biome/oxlint, pnpm, tsgo)
- Commit-time impact: faster every commit (3 full-repo scans → pre-push, 2 bash hooks gone)

### Verdict

Modern stack with localized debt: re-arm detekt, delete the redundant bash/bandit hooks, unify the HTTP-client retry path, drop the dead build-arg — leave the (justified) ESLint+Prettier stack and BuildSha contract alone.

Scouts/synthesis: 4/2.

## 2026-06-14 17:30 · d4221fce · full leverage audit (session diff 430b35b0..HEAD: enricher, audiobook/discover, frontend, #226) — 4 scouts / 2 synthesis

### TL;DR (full audit)

Mostly tidy. The genuinely high-value, low-risk wins were APPLIED this pass; the two largest "🔴" duplication claims were correctly downgraded by synthesis as false leverage (forcing divergent behavior into one flag-driven blob), and the headline tooling/library swaps were rejected at parity.

### Applied this pass ✅

1. ✅ Dropped `MetadataEnricher.representativeReleaseMbid` + `MusicBrainzClient.searchReleases` + 3 orphan DTOs (`MbReleaseSearch/MbRelease/MbReleaseGroupRef`). It fired a **2nd rate-limited MusicBrainz round-trip per album** to write `album.musicbrainzId`, which is **write-only dead data** (domain `Album` has no such field — `Types.kt:55` is Artist; `LibraryMappers.toAlbum` never maps it). Halves per-album enrichment latency, removes ~40 lines.
2. ✅ Deleted the dead `imageRepo.findByEntityIdAndIsPrimaryTrue?.let{delete}` in `downloadCoverIfMissing` (the guard at the top already returns when a primary exists).
3. ✅ Collapsed `useDiscover`/`useDailyMix` → shared `useRecommendationFeed(queryKey, fetch, limit)` factory; merged service `getDailyMix`/`getDiscover` → private `getRecommendationFeed(feed, limit)`. ~25 lines, behavior-identical (type-check + build green).

### Deferred (worth-it, Medium effort — not applied)

- `<TrackSection>` _presentational shell_ for ExploreNew/DailyMix/FavoriteSongs — extract the shared `<section>`+header+load/error/empty switch ONLY; keep thin per-feature wrappers (their empty-policy, header-action, and data source genuinely diverge). ~110–130 lines (NOT the 170 first claimed).
- Controller `recommendationResponse` + `musicSurfaceFilter` helpers (~12–15 lines; keep the distinct seed-collection loops).
- `wait-backend-rollout` composite action to de-dupe the ~22-line poll across `ci.yml`/`v2-ci.yml`.
- `no-console` → `error` (drop bash hook); drop `bandit` pre-commit hook (ruff `S` already covers it); `include-git-sha: false` for the backend image (dead build-arg).

### Rejected as false leverage / not worth it

- **`ProviderHttpClient`** merging the two enricher HTTP clients — they diverge on body type, retry (loop vs single-shot), redirect/HTTP-version, and timeout; the generic needs 6 policy knobs over ~4 shared lines. REFUTED.
- **detekt re-enable as "drop-in"** — it's a real Kotlin-2.1 / detekt-1.23.7 incompatibility needing a detekt-2.x upgrade + findings triage, not a 4-line deletion. A quality decision, not free leverage.
- **`getRecentAlbums` "dead"** — NOT dead; referenced by a core integration test.
- **`excludeGenres` "5-layer threading"** — that IS the mandated hexagonal port path, not over-engineering.
- **Hand-rolled Levenshtein / RateLimiter / retry** — commons-text/spring-retry/resilience4j not on classpath; net-new dep unjustified for a 1 req/s worker. KEEP.
- **Biome/oxlint/pnpm/tsgo** — parity gaps (no `eslint-plugin-boundaries` equivalent for Core→Platform→Web layering; tsgo beta).

### Total Estimated Savings (full audit)

Applied: ~65 lines removed + one MusicBrainz round-trip/album eliminated. Deferred (if pursued): ~150 more lines + 1 composite action + 2 pre-commit hooks. New deps: none.

Scouts/synthesis: 4/2.
