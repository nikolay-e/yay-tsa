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

const iconSizes = [120, 152, 167, 180, 192, 512];
const splashSizes = [
  { width: 750, height: 1334, name: 'iPhone 8, 7, 6s, 6' },
  { width: 828, height: 1792, name: 'iPhone 11, XR' },
  { width: 1125, height: 2436, name: 'iPhone 11 Pro, X, XS' },
  { width: 1170, height: 2532, name: 'iPhone 12/13/14 Pro' },
  { width: 1179, height: 2556, name: 'iPhone 14 Pro' },
  { width: 1284, height: 2778, name: 'iPhone 11 Pro Max, XS Max' },
  { width: 1290, height: 2796, name: 'iPhone 14 Pro Max' },
];
const staticDir = resolve(__dirname, '../static');
const svgPath = resolve(staticDir, 'icon.svg');

async function generateIcons() {
  console.log('📦 Generating PNG icons from icon.svg...\n');

  const svgBuffer = readFileSync(svgPath);

  // Generate app icons
  for (const size of iconSizes) {
    const outputPath = resolve(staticDir, `icon-${size}.png`);
    await sharp(svgBuffer).resize(size, size).png().toFile(outputPath);
    console.log(`✅ Generated: icon-${size}.png (${size}x${size})`);
  }

  // Generate favicon.png (32x32)
  const faviconPath = resolve(staticDir, 'favicon.png');
  await sharp(svgBuffer).resize(32, 32).png().toFile(faviconPath);
  console.log(`✅ Generated: favicon.png (32x32)`);

  console.log('\n📱 Generating splash screens...\n');

  // Generate splash screens with centered icon on black background
  for (const splash of splashSizes) {
    const outputPath = resolve(staticDir, `splash-${splash.width}x${splash.height}.png`);

    // Create black background with centered icon (1/3 of screen width)
    const iconSize = Math.floor(splash.width / 3);

    await sharp({
      create: {
        width: splash.width,
        height: splash.height,
        channels: 4,
        background: { r: 15, g: 15, b: 15, alpha: 1 }, // #0f0f0f
      },
    })
      .composite([
        {
          input: await sharp(svgBuffer).resize(iconSize, iconSize).png().toBuffer(),
          top: Math.floor((splash.height - iconSize) / 2),
          left: Math.floor((splash.width - iconSize) / 2),
        },
      ])
      .png()
      .toFile(outputPath);

    console.log(`✅ Generated: splash-${splash.width}x${splash.height}.png (${splash.name})`);
  }

  console.log('\n🎉 All icons and splash screens generated successfully!');
}

generateIcons().catch(error => {
  console.error('❌ Error generating icons:', error);
  process.exit(1);
});
