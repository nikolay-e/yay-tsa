#!/usr/bin/env node

import { readFileSync, writeFileSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const rootDir = join(__dirname, '..');

const rootPkg = JSON.parse(readFileSync(join(rootDir, 'package.json'), 'utf-8'));
const version = rootPkg.version;
const cacheVersion = `v${version}`;

console.log(`Syncing version ${version} across all packages...`);

const filesToUpdate = [
  {
    path: 'packages/core/package.json',
    type: 'json',
    field: 'version',
  },
  {
    path: 'packages/platform/package.json',
    type: 'json',
    field: 'version',
  },
  {
    path: 'packages/web/package.json',
    type: 'json',
    field: 'version',
  },
  {
    path: 'packages/web/static/manifest.json',
    type: 'json',
    fields: ['version', { field: 'start_url', template: `/?v=${version}` }],
  },
  {
    path: 'packages/web/static/config.js',
    type: 'replace',
    pattern: /version:\s*['"][^'"]+['"]/,
    replacement: `version: '${version}'`,
  },
  {
    path: 'packages/core/src/config/constants.ts',
    type: 'replace',
    pattern: /APP_VERSION\s*=\s*['"][^'"]+['"]/,
    replacement: `APP_VERSION = '${version}'`,
  },
  {
    path: 'packages/web/vite.config.ts',
    type: 'replace',
    replacements: [
      {
        pattern: /cacheName:\s*'yaytsa-images-v[^']+'/g,
        replacement: `cacheName: 'yaytsa-images-${cacheVersion}'`,
      },
      {
        pattern: /cacheName:\s*'yaytsa-audio-v[^']+'/g,
        replacement: `cacheName: 'yaytsa-audio-${cacheVersion}'`,
      },
    ],
  },
  {
    path: 'packages/web/src/lib/stores/auth.ts',
    type: 'replace',
    pattern: /const cacheNames = \['yaytsa-images-v[^']+', 'yaytsa-audio-v[^']+'\]/,
    replacement: `const cacheNames = ['yaytsa-images-${cacheVersion}', 'yaytsa-audio-${cacheVersion}']`,
  },
];

let updatedCount = 0;

for (const file of filesToUpdate) {
  const filePath = join(rootDir, file.path);

  try {
    if (file.type === 'json') {
      const content = JSON.parse(readFileSync(filePath, 'utf-8'));
      let changed = false;

      if (file.field) {
        if (content[file.field] !== version) {
          content[file.field] = version;
          changed = true;
        }
      }

      if (file.fields) {
        for (const fieldDef of file.fields) {
          if (typeof fieldDef === 'string') {
            if (content[fieldDef] !== version) {
              content[fieldDef] = version;
              changed = true;
            }
          } else if (fieldDef.field && fieldDef.template) {
            if (content[fieldDef.field] !== fieldDef.template) {
              content[fieldDef.field] = fieldDef.template;
              changed = true;
            }
          }
        }
      }

      if (changed) {
        writeFileSync(filePath, JSON.stringify(content, null, 2) + '\n', 'utf-8');
        console.log(`  ✓ Updated ${file.path}`);
        updatedCount++;
      } else {
        console.log(`  - ${file.path} (already up to date)`);
      }
    } else if (file.type === 'replace') {
      let content = readFileSync(filePath, 'utf-8');
      let newContent = content;

      if (Array.isArray(file.replacements)) {
        for (const r of file.replacements) {
          newContent = newContent.replace(r.pattern, r.replacement);
        }
      } else {
        newContent = content.replace(file.pattern, file.replacement);
      }

      if (content !== newContent) {
        writeFileSync(filePath, newContent, 'utf-8');
        console.log(`  ✓ Updated ${file.path}`);
        updatedCount++;
      } else {
        console.log(`  - ${file.path} (already up to date)`);
      }
    }
  } catch (error) {
    console.error(`  ✗ Failed to update ${file.path}: ${error.message}`);
  }
}

console.log(`\nSync complete: ${updatedCount} file(s) updated to version ${version}`);
