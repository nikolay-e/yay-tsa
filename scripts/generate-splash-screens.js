#!/usr/bin/env node

import sharp from 'sharp';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const STATIC_DIR = path.join(__dirname, '../packages/web/static');
const ICON_PATH = path.join(STATIC_DIR, 'icon-512.png');
const BG_COLOR = { r: 0, g: 0, b: 0 }; // True black for OLED

const SPLASH_SIZES = [
  { name: 'splash-1290x2796', width: 1290, height: 2796 }, // iPhone 14 Pro Max
  { name: 'splash-1179x2556', width: 1179, height: 2556 }, // iPhone 14 Pro
  { name: 'splash-1284x2778', width: 1284, height: 2778 }, // iPhone 13 Pro Max
  { name: 'splash-1170x2532', width: 1170, height: 2532 }, // iPhone 13/14
  { name: 'splash-1125x2436', width: 1125, height: 2436 }, // iPhone X/XS/11 Pro
  { name: 'splash-828x1792', width: 828, height: 1792 }, // iPhone 11/XR
  { name: 'splash-750x1334', width: 750, height: 1334 }, // iPhone SE
];

async function generateSplashScreen(size) {
  const { name, width, height } = size;
  const outputPath = path.join(STATIC_DIR, `${name}.png`);

  // Icon size: 30% of shortest dimension
  const iconSize = Math.floor(Math.min(width, height) * 0.3);

  console.log(`Creating ${name}.png (${width}x${height}) with ${iconSize}px icon...`);

  try {
    // Resize icon to appropriate size
    const resizedIcon = await sharp(ICON_PATH)
      .resize(iconSize, iconSize, { fit: 'contain' })
      .toBuffer();

    // Create background with centered icon
    await sharp({
      create: {
        width,
        height,
        channels: 4,
        background: BG_COLOR,
      },
    })
      .composite([
        {
          input: resizedIcon,
          gravity: 'center',
        },
      ])
      .png({ quality: 100, compressionLevel: 9 })
      .toFile(outputPath);

    console.log(`✅ Created ${name}.png`);
    return true;
  } catch (error) {
    console.error(`❌ Failed to create ${name}.png:`, error.message);
    return false;
  }
}

async function main() {
  console.log('🎨 Generating iOS splash screens...\n');

  if (!fs.existsSync(ICON_PATH)) {
    console.error(`❌ Icon not found: ${ICON_PATH}`);
    process.exit(1);
  }

  const results = await Promise.all(SPLASH_SIZES.map(size => generateSplashScreen(size)));

  const successCount = results.filter(Boolean).length;

  console.log(`\n✨ Generated ${successCount}/${SPLASH_SIZES.length} splash screens successfully!`);
  console.log(`📁 Location: ${STATIC_DIR}/splash-*.png`);

  if (successCount < SPLASH_SIZES.length) {
    process.exit(1);
  }
}

main().catch(error => {
  console.error('Fatal error:', error);
  process.exit(1);
});
