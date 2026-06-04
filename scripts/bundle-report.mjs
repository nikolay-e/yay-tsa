#!/usr/bin/env node
/**
 * Bundle profiler: builds (or reuses) apps/web/dist and prints the JS/CSS chunk table with raw and
 * gzipped sizes, plus the initial critical-path total (entry + eager vendor chunks + CSS, i.e. what
 * the browser must download before the first route renders). Reproducible: `npm run profile:bundle`.
 */
import { readdirSync, statSync, readFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { join } from 'node:path';

const dist = join(process.cwd(), 'apps/web/dist/assets');
let files;
try {
  files = readdirSync(dist).filter(f => /\.(js|css)$/.test(f));
} catch {
  console.error('No build found. Run `npm run build` first (or `npm run profile:bundle:build`).');
  process.exit(1);
}

const rows = files
  .map(f => {
    const p = join(dist, f);
    const raw = statSync(p).size;
    const gz = gzipSync(readFileSync(p)).length;
    return { file: f, raw, gz };
  })
  .sort((a, b) => b.gz - a.gz);

// Eager critical path = entry (index-*.js) + vendor-* chunks + the main CSS. Lazy route chunks
// (named after pages) are excluded since they load on navigation, not at boot.
const isCritical = f =>
  /^index-.*\.js$/.test(f) ||
  /^vendor-.*\.js$/.test(f) ||
  /^cn-.*\.js$/.test(f) ||
  /\.css$/.test(f);

const fmt = n => `${(n / 1024).toFixed(1)}kB`;
console.log('\n=== chunk sizes (gzipped, descending) ===');
console.log('  gzip      raw       chunk');
for (const r of rows) {
  console.log(
    `  ${fmt(r.gz).padEnd(9)} ${fmt(r.raw).padEnd(9)} ${r.file}${isCritical(r.file) ? '  [eager/critical]' : ''}`
  );
}

const critical = rows.filter(r => isCritical(r.file));
const sum = (arr, k) => arr.reduce((s, r) => s + r[k], 0);
console.log('\n=== totals ===');
console.log(
  `  all JS:           ${fmt(
    sum(
      rows.filter(r => r.file.endsWith('.js')),
      'gz'
    )
  )} gz`
);
console.log(`  initial critical: ${fmt(sum(critical, 'gz'))} gz  (entry + eager vendors + CSS)`);
console.log(`  repo target:      150.0kB gz (CLAUDE.md performance target)`);
