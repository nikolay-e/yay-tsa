#!/bin/bash
set -e

BRANCH=$(git rev-parse --abbrev-ref HEAD)

if [[ "$BRANCH" != "main" ]]; then
  exit 0
fi

LAST_COMMIT=$(git log -1 --format=%s)
if [[ "$LAST_COMMIT" == "chore: bump version" ]]; then
  exit 0
fi

npm version patch --no-git-tag-version
npm run sync:version
git add package.json packages/*/package.json packages/web/static/manifest.json packages/web/static/config.js packages/core/src/config/constants.ts packages/web/vite.config.ts packages/web/src/lib/stores/auth.ts
git commit -m "chore: bump version" --no-verify
