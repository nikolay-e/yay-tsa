#!/usr/bin/env node
/**
 * Image cost profiler (`npm run profile:images`).
 *
 * The backend serves the ORIGINAL cover file (JellyfinMediaController.getImage ignores
 * maxWidth/maxHeight/quality), so every cover is delivered at source resolution. This quantifies the
 * download/decode cost of that and the savings from serving display-appropriate, WebP-encoded
 * thumbnails. It uses a representative synthetic cover (gaussian-noise base ≈ photographic entropy)
 * because the sandbox has no real library; real album art varies, but the dimension/format ratios
 * are robust. Run against real covers by pointing SRC at an image file.
 */
import sharp from 'sharp';
import { readFileSync } from 'node:fs';

const SRC = process.env.SRC;
const bytes = b => `${(b / 1024).toFixed(1)} kB`;

async function source() {
  if (SRC) return readFileSync(SRC);
  // Representative ~1200x1200 cover with structure (gradient + shapes + text) so it compresses and
  // downscales like real album art — gaussian noise alone flattens on resize and overstates savings.
  const svg = Buffer.from(`
    <svg xmlns="http://www.w3.org/2000/svg" width="1200" height="1200">
      <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stop-color="#3b0764"/><stop offset="50%" stop-color="#9d174d"/>
        <stop offset="100%" stop-color="#f59e0b"/></linearGradient></defs>
      <rect width="1200" height="1200" fill="url(#g)"/>
      <circle cx="380" cy="430" r="300" fill="#ffffff" opacity="0.18"/>
      <circle cx="860" cy="800" r="220" fill="#000000" opacity="0.22"/>
      <rect x="120" y="980" width="960" height="120" rx="16" fill="#000000" opacity="0.35"/>
      <text x="150" y="1065" font-family="sans-serif" font-size="74" fill="#ffffff">Album Title</text>
      <text x="150" y="250" font-family="sans-serif" font-size="120" font-weight="bold" fill="#ffffff" opacity="0.9">YT</text>
    </svg>`);
  return sharp(svg).jpeg({ quality: 85 }).toBuffer();
}

async function variant(buf, width, format) {
  const img = sharp(buf).resize(width, width, { fit: 'cover' });
  const out = format === 'webp' ? img.webp({ quality: 75 }) : img.jpeg({ quality: 80 });
  return (await out.toBuffer()).length;
}

const src = await source();
const srcLen = src.length;
const meta = await sharp(src).metadata();

const targets = [
  ['source (served today, full-res)', meta.width, 'orig', srcLen],
  [
    '300px JPEG (current request size, if backend resized)',
    300,
    'jpeg',
    await variant(src, 300, 'jpeg'),
  ],
  ['300px WebP', 300, 'webp', await variant(src, 300, 'webp')],
  ['160px JPEG (grid thumbnail)', 160, 'jpeg', await variant(src, 160, 'jpeg')],
  ['160px WebP (grid thumbnail)', 160, 'webp', await variant(src, 160, 'webp')],
  ['48px WebP (track row)', 48, 'webp', await variant(src, 48, 'webp')],
];

console.log(
  `\nSource cover: ${meta.width}x${meta.height} ${meta.format}, ${bytes(srcLen)}` +
    (SRC ? ` (${SRC})` : ' (synthetic representative)')
);
console.log('\n  variant                                                   bytes      vs source');
for (const [label, , , len] of targets) {
  const pct = ((1 - len / srcLen) * 100).toFixed(1);
  const pctLabel = len === srcLen ? '—' : `-${pct}%`;
  console.log(`  ${label.padEnd(54)} ${bytes(len).padStart(9)}   ${pctLabel}`);
}

const home = 38;
const grid160 = await variant(src, 160, 'webp');
const reduction = 1 - grid160 / srcLen;
// Absolute Home bytes depend on the REAL source size, not the synthetic; parameterize on a typical
// 1200px photographic cover (~250 kB; override with TYPICAL_SRC_KB) and apply the measured ratio.
const typicalSrcKB = Number(process.env.TYPICAL_SRC_KB ?? 250);
console.log(
  `\nHome page (~${home} covers near fold), assuming a typical ${typicalSrcKB} kB real cover:`
);
console.log(`  served today (full-res):   ~${((typicalSrcKB * home) / 1024).toFixed(1)} MB`);
console.log(
  `  160px WebP thumbnails:      ~${((typicalSrcKB * (1 - reduction) * home) / 1024).toFixed(2)} MB  (measured -${(reduction * 100).toFixed(0)}%)`
);

// Exact, format-independent: decode cost scales with pixel count.
const areaRatio = (w, base) => `${(((w * w) / (base * base)) * 100).toFixed(1)}%`;
console.log(`\nPixel-area vs ${meta.width}px source (decode cost ∝ pixels, certain):`);
console.log(
  `  300px = ${areaRatio(300, meta.width)} of pixels   160px = ${areaRatio(160, meta.width)}   48px = ${areaRatio(48, meta.width)}`
);
console.log(
  '\nNote: synthetic structured source — real covers vary in absolute size; the ratios are robust.'
);
