#!/bin/bash
set -e

BRANCH=$(git rev-parse --abbrev-ref HEAD)

if [[ "$BRANCH" != "main" ]]; then
  exit 0
fi

npm version patch --no-git-tag-version
npm run sync:version
git add package.json packages/*/package.json
git commit -m "chore: bump version" --no-verify
