#!/usr/bin/env node
import { readFileSync, writeFileSync } from 'fs';
import { createHash } from 'crypto';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const htmlPath = join(__dirname, '../dist/index.html');
const outputPath = join(__dirname, '../dist/.csp-hashes.json');

try {
  const html = readFileSync(htmlPath, 'utf-8');

  const inlineScriptRegex = /<script(?![^>]*\ssrc=)>([\s\S]*?)<\/script>/g;
  const hashes = [];

  let match;
  while ((match = inlineScriptRegex.exec(html)) !== null) {
    const scriptContent = match[1];
    const hash = createHash('sha256').update(scriptContent).digest('base64');
    hashes.push(`sha256-${hash}`);
    // eslint-disable-next-line no-console
    console.log(`Generated CSP hash for inline script: sha256-${hash}`);
  }

  writeFileSync(outputPath, JSON.stringify({ scriptHashes: hashes }, null, 2));

  // eslint-disable-next-line no-console
  console.log(`✓ CSP hashes generated: ${hashes.length} inline script(s) found`);
  // eslint-disable-next-line no-console
  console.log(`✓ Saved to: ${outputPath}`);
} catch (error) {
  console.error('Failed to generate CSP hashes:', error);
  process.exit(1);
}
