/**
 * Lightweight, opt-in boot/render profiler. Disabled by default so there is no production logging
 * noise. Enable with `?perf` in the URL or `localStorage.setItem('yaytsa_perf','1')`.
 *
 * Captures:
 *  - custom boot marks (app_start, auth_restore_*, first_route_render, first_query_*, items_render_*,
 *    first_cover_loaded) via performance.mark/measure;
 *  - browser metrics from PerformanceObserver: TTFB (navigation), FCP (paint), LCP, and long tasks
 *    (a TBT proxy).
 *
 * Call installPerf() once at boot, mark()/measure() at the instrumented points, and read the table
 * via window.__perfDump() in the console (or it auto-dumps a few seconds after load when enabled).
 */

const PREFIX = 'yaytsa';

function readEnabled(): boolean {
  if (typeof window === 'undefined') return false;
  try {
    if (new URLSearchParams(window.location.search).has('perf')) return true;
    return window.localStorage.getItem('yaytsa_perf') === '1';
  } catch {
    return false;
  }
}

const enabled = readEnabled();

type PerfMarkName =
  | 'app_start'
  | 'auth_restore_start'
  | 'auth_restore_end'
  | 'first_route_render'
  | 'first_query_start'
  | 'first_query_end'
  | 'items_render_start'
  | 'items_render_end'
  | 'first_cover_loaded';

export function mark(name: PerfMarkName | string): void {
  if (!enabled || typeof performance === 'undefined') return;
  try {
    performance.mark(`${PREFIX}:${name}`);
  } catch {
    /* ignore */
  }
}

/** Mark a name only the first time it's seen (e.g. first_route_render across many renders). */
const onceSeen = new Set<string>();
export function markOnce(name: PerfMarkName | string): void {
  if (!enabled || onceSeen.has(name)) return;
  onceSeen.add(name);
  mark(name);
}

export function measure(name: string, startMark: string, endMark: string): void {
  if (!enabled || typeof performance === 'undefined') return;
  try {
    performance.measure(`${PREFIX}:${name}`, `${PREFIX}:${startMark}`, `${PREFIX}:${endMark}`);
  } catch {
    /* missing marks — ignore */
  }
}

const vitals: Record<string, number> = {};

function observe(type: string, cb: (entry: PerformanceEntry) => void): void {
  try {
    new PerformanceObserver(list => list.getEntries().forEach(cb)).observe({
      type,
      buffered: true,
    });
  } catch {
    /* unsupported entry type — ignore */
  }
}

export function installPerf(): void {
  if (!enabled || typeof window === 'undefined') return;

  observe('paint', e => {
    if (e.name === 'first-contentful-paint') vitals.FCP = Math.round(e.startTime);
  });
  observe('largest-contentful-paint', e => {
    vitals.LCP = Math.round(e.startTime);
  });
  observe('navigation', e => {
    const n = e as PerformanceNavigationTiming;
    vitals.TTFB = Math.round(n.responseStart);
    vitals.DOMContentLoaded = Math.round(n.domContentLoadedEventEnd);
    vitals.Load = Math.round(n.loadEventEnd);
  });
  let tbt = 0;
  observe('longtask', e => {
    tbt += Math.max(0, e.duration - 50);
    vitals.TBT_proxy = Math.round(tbt);
  });

  (window as unknown as { __perfDump: () => void }).__perfDump = dumpPerf;
  // Auto-dump once the page has settled so a quick `?perf` run needs no console interaction.
  window.addEventListener('load', () => {
    setTimeout(dumpPerf, 4000);
  });
}

function dumpPerf(): void {
  if (typeof performance === 'undefined') return;
  const measures = performance
    .getEntriesByType('measure')
    .filter(m => m.name.startsWith(`${PREFIX}:`))
    .map(m => ({ measure: m.name.replace(`${PREFIX}:`, ''), ms: Math.round(m.duration) }));
  const marks = performance
    .getEntriesByType('mark')
    .filter(m => m.name.startsWith(`${PREFIX}:`))
    .map(m => ({ mark: m.name.replace(`${PREFIX}:`, ''), at_ms: Math.round(m.startTime) }))
    .sort((a, b) => a.at_ms - b.at_ms);

  /* eslint-disable no-console */
  console.group('%c[yaytsa perf]', 'color:#6cf');
  console.table(vitals);
  console.table(marks);
  if (measures.length) console.table(measures);
  console.groupEnd();
  /* eslint-enable no-console */
}
