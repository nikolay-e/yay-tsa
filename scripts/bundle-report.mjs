#!/usr/bin/env node
/**
 * Bundle profiler: builds (or reuses) apps/web/dist and prints the JS/CSS chunk table with raw and
 * gzipped sizes, plus the initial critical-path total (entry + eager vendor chunks + CSS, i.e. what
 * the browser must download before the first route renders). Reproducible: `npm run profile:bundle`.
 */
import { readdirSync, statSync, readFileSync, existsSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { join } from 'node:path';

const distRoot = join(process.cwd(), 'apps/web/dist');
const dist = join(distRoot, 'assets');
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

// Eager critical path = exactly what index.html references (entry script, modulepreloaded
// chunks, CSS). Lazy route chunks load on navigation, not at boot. Falls back to a name
// heuristic when index.html is absent.
const eagerSetFromIndexHtml = () => {
  const indexHtml = join(distRoot, 'index.html');
  if (!existsSync(indexHtml)) return null;
  const refs = [
    ...readFileSync(indexHtml, 'utf8').matchAll(/\/assets\/([A-Za-z0-9._-]+\.(?:js|css))/g),
  ].map(m => m[1]);
  return refs.length ? new Set(refs) : null;
};
const eagerSet = eagerSetFromIndexHtml();
const isCritical = f =>
  eagerSet
    ? eagerSet.has(f)
    : /^index-.*\.js$/.test(f) ||
      /^vendor-.*\.js$/.test(f) ||
      /^cn-.*\.js$/.test(f) ||
      f.endsWith('.css');

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
const eagerJsGzip = sum(
  critical.filter(r => r.file.endsWith('.js')),
  'gz'
);
console.log(`  initial critical: ${fmt(sum(critical, 'gz'))} gz  (entry + eager vendors + CSS)`);
console.log(`  eager JS:         ${fmt(eagerJsGzip)} gz  (index.html-referenced JS only)`);
console.log(`  repo target:      150.0kB gz (CLAUDE.md performance target)`);

const maxTotalArg = process.argv.find(a => a.startsWith('--max-total-gzip='));
if (maxTotalArg) {
  const maxKb = Number(maxTotalArg.split('=')[1]);
  if (!Number.isFinite(maxKb) || maxKb <= 0) {
    console.error(`Invalid flag value: ${maxTotalArg} (expected --max-total-gzip=<kB>)`);
    process.exit(2);
  }
  if (eagerJsGzip > maxKb * 1024) {
    console.error(
      `\nFAIL: eager JS total ${fmt(eagerJsGzip)} gz exceeds the ${maxKb.toFixed(1)}kB gz budget`
    );
    process.exit(1);
  }
  console.log(`  budget:           ${maxKb.toFixed(1)}kB gz eager JS — OK`);
}
