#!/usr/bin/env node
/**
 * Browser load profiler (Playwright). Reproducible: `npm run profile:web`.
 *
 * Collects, per page and scenario, over N runs: TTFB, FCP, LCP, DOMContentLoaded, Load, the custom
 * yaytsa:* boot marks (app_start, auth_restore, first_route_render — see src/shared/perf/perf.ts),
 * total requests, and transferred bytes. Prints min / median / max and writes traces under
 * profile-artifacts/.
 *
 * Requires a browser (this sandbox blocks the Playwright Chromium download, so it can't run here —
 * run it on a machine where `npx playwright install chromium` succeeds).
 *
 * Env:
 *   BASE_URL       target origin (default http://localhost:5173) — appends ?perf to enable marks
 *   STORAGE_STATE  optional Playwright storageState json (a logged-in session) for warm/PWA scenarios
 *   RUNS           runs per scenario (default 5)
 *   PAGES          comma list of routes (default /,/albums,/songs,/favorites,/artists)
 */
import { chromium } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:5173';
const RUNS = Number(process.env.RUNS ?? 5);
const PAGES = (process.env.PAGES ?? '/,/albums,/songs,/favorites,/artists').split(',');
const STORAGE_STATE = process.env.STORAGE_STATE;
const OUT = 'profile-artifacts';
mkdirSync(OUT, { recursive: true });

const withPerf = path => `${BASE_URL}${path}${path.includes('?') ? '&' : '?'}perf`;
const median = xs => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)];

async function measurePage(browser, path, scenario) {
  const samples = [];
  for (let i = 0; i < RUNS; i++) {
    // cold = fresh context (no cache/storage); warm/pwa = reuse storageState
    const context = await browser.newContext(
      scenario === 'cold' ? {} : STORAGE_STATE ? { storageState: STORAGE_STATE } : {}
    );
    const page = await context.newPage();
    let requests = 0;
    let bytes = 0;
    page.on('response', async res => {
      requests++;
      try {
        const len = Number(res.headers()['content-length'] ?? 0);
        bytes += len || (await res.body().catch(() => Buffer.alloc(0))).length;
      } catch {
        /* ignore */
      }
    });
    const traceFile = `${OUT}/${scenario}${path.replace(/\W+/g, '_')}_${i}.json`;
    await page.goto(withPerf(path), { waitUntil: 'load', timeout: 30000 });
    // Give marks/LCP a moment to settle, then read performance entries from the page.
    await page.waitForTimeout(2500);
    const m = await page.evaluate(() => {
      const get = (type, name) =>
        performance.getEntriesByType(type).find(e => e.name === name)?.startTime ?? null;
      const nav = performance.getEntriesByType('navigation')[0];
      const markAt = n => performance.getEntriesByName(`yaytsa:${n}`, 'mark')[0]?.startTime ?? null;
      const lcp = performance.getEntriesByType('largest-contentful-paint').pop();
      return {
        TTFB: nav ? Math.round(nav.responseStart) : null,
        FCP: Math.round(get('paint', 'first-contentful-paint') ?? 0) || null,
        LCP: lcp ? Math.round(lcp.startTime) : null,
        DCL: nav ? Math.round(nav.domContentLoadedEventEnd) : null,
        Load: nav ? Math.round(nav.loadEventEnd) : null,
        app_start: markAt('app_start'),
        auth_restore_end: markAt('auth_restore_end'),
        first_route_render: markAt('first_route_render'),
      };
    });
    writeFileSync(traceFile, JSON.stringify({ path, scenario, ...m, requests, bytes }, null, 2));
    samples.push({ ...m, requests, bytes });
    await context.close();
  }
  const col = k => samples.map(s => s[k]).filter(v => v != null);
  const stat = k => {
    const xs = col(k);
    return xs.length ? `${Math.min(...xs)}/${median(xs)}/${Math.max(...xs)}` : '-';
  };
  return {
    page: path,
    scenario,
    'TTFB min/med/max': stat('TTFB'),
    'FCP min/med/max': stat('FCP'),
    'LCP min/med/max': stat('LCP'),
    'first_route min/med/max': stat('first_route_render'),
    'reqs med': median(col('requests')) ?? '-',
    'KB med': Math.round((median(col('bytes')) ?? 0) / 1024),
  };
}

const browser = await chromium.launch();
const rows = [];
for (const scenario of ['cold', 'warm']) {
  for (const path of PAGES) rows.push(await measurePage(browser, path, scenario));
}
await browser.close();
console.table(rows);
console.log(`\nTraces written to ${OUT}/ (one json per run). Median over ${RUNS} runs.`);
