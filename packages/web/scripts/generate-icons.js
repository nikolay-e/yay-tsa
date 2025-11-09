#!/usr/bin/env node

/**
 * Generate PNG icons from icon.svg
 * Converts SVG to multiple PNG sizes for PWA manifest
 */

import sharp from 'sharp';
import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const sizes = [120, 152, 167, 180, 192, 512];
const staticDir = resolve(__dirname, '../static');
const svgPath = resolve(staticDir, 'icon.svg');

async function generateIcons() {
  console.log('📦 Generating PNG icons from icon.svg...\n');

  const svgBuffer = readFileSync(svgPath);

  for (const size of sizes) {
    const outputPath = resolve(staticDir, `icon-${size}.png`);

    await sharp(svgBuffer).resize(size, size).png().toFile(outputPath);

    console.log(`✅ Generated: icon-${size}.png (${size}x${size})`);
  }

  // Generate favicon.png (32x32)
  const faviconPath = resolve(staticDir, 'favicon.png');
  await sharp(svgBuffer).resize(32, 32).png().toFile(faviconPath);
  console.log(`✅ Generated: favicon.png (32x32)`);

  console.log('\n🎉 All icons generated successfully!');
}

generateIcons().catch(error => {
  console.error('❌ Error generating icons:', error);
  process.exit(1);
});
